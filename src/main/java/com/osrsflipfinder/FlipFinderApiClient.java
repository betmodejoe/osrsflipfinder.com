package com.osrsflipfinder;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Thin async wrapper over RuneLite's shared OkHttp client for talking to the
 * OSRS Flip Finder ingest API. All calls are non-blocking (OkHttp's own
 * dispatcher), so they're safe to invoke from the client thread.
 *
 * <p>Transient failures — a network blip or, most commonly, a serverless cold
 * start where the function and database take a moment to wake — are retried with
 * backoff rather than surfaced, so the first request to an idle backend never
 * shows a spurious "Could not reach". Only a definitive outcome (success, a 401
 * bad key, or a failure that persists through every retry) is reported back.
 */
@Slf4j
@Singleton
public class FlipFinderApiClient
{
	/** Production OSRS Flip Finder endpoint — fixed, not user-configurable. */
	private static final String BASE_URL = "https://osrsflipfinder.com";

	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

	/**
	 * Delay before each successive retry (ms). The total (~31s) plus the
	 * per-attempt call timeout comfortably outlasts any realistic Vercel cold
	 * start + Neon resume, so a cold backend resolves on a later attempt instead
	 * of failing.
	 */
	private static final long[] RETRY_BACKOFF_MS = {1_000, 2_000, 4_000, 8_000, 16_000};
	private static final int MAX_ATTEMPTS = RETRY_BACKOFF_MS.length + 1;

	private final OkHttpClient okHttpClient;
	private final Gson gson;
	private final ScheduledExecutorService executor;

	@Inject
	private FlipFinderApiClient(OkHttpClient okHttpClient, Gson gson, ScheduledExecutorService executor)
	{
		// Reuse RuneLite's shared connection pool/dispatcher but extend the
		// timeouts: the ingest API is serverless and a cold start can take well
		// over the default ~10s read timeout. A 35s overall cap lets a single
		// cold start complete; the retry loop below covers anything beyond that.
		this.okHttpClient = okHttpClient.newBuilder()
			.connectTimeout(15, TimeUnit.SECONDS)
			.readTimeout(30, TimeUnit.SECONDS)
			.writeTimeout(15, TimeUnit.SECONDS)
			.callTimeout(35, TimeUnit.SECONDS)
			.build();
		this.gson = gson;
		this.executor = executor;
	}

	/** Result of an API call, delivered on an OkHttp background thread. */
	public interface ResultCallback
	{
		void onResult(boolean ok, String message);
	}

	/** Suggestions result (by item id), delivered on an OkHttp background thread. */
	public interface SuggestionsCallback
	{
		void onSuccess(Map<Integer, Suggestion> byItemId);

		void onError(String message);
	}

	/** Transport outcome: HTTP code (-1 when there was no response) and cause. */
	private interface TransportCallback
	{
		void onComplete(boolean success, int code, IOException error);
	}

	/** Wrapper so the JSON body is { "transactions": [...] } as the server expects. */
	private static final class Payload
	{
		final List<GeSyncTx> transactions;

		Payload(List<GeSyncTx> transactions)
		{
			this.transactions = transactions;
		}
	}

	/** Mirrors the per-item-id suggestions map returned by /api/suggest. */
	private static final class SuggestResponse
	{
		Map<String, Suggestion> suggestions;
	}

	/** POST a batch of GE updates to /api/sync/trades. */
	public void submit(String apiKey, List<GeSyncTx> fills, ResultCallback cb)
	{
		if (apiKey == null || apiKey.trim().isEmpty())
		{
			cb.onResult(false, "API key not set");
			return;
		}

		final String json = gson.toJson(new Payload(fills));
		final Request request = new Request.Builder()
			.url(BASE_URL + "/api/sync/trades")
			.header("Authorization", "Bearer " + apiKey.trim())
			.post(RequestBody.create(JSON, json))
			.build();

		send(request, (success, code, error) ->
		{
			if (!success && error != null)
			{
				log.warn("Flip Finder sync request failed after {} attempts", MAX_ATTEMPTS, error);
				cb.onResult(false, error.getMessage());
			}
			else
			{
				cb.onResult(success, "HTTP " + code);
			}
		});
	}

	/** GET /api/sync/me to verify the key (the "Test connection" button). */
	public void testConnection(String apiKey, ResultCallback cb)
	{
		if (apiKey == null || apiKey.trim().isEmpty())
		{
			cb.onResult(false, "Set your API key first");
			return;
		}

		final Request request = new Request.Builder()
			.url(BASE_URL + "/api/sync/me")
			.header("Authorization", "Bearer " + apiKey.trim())
			.get()
			.build();

		send(request, (success, code, error) ->
		{
			if (success)
			{
				cb.onResult(true, "Connected");
			}
			else if (code == 401)
			{
				cb.onResult(false, "Invalid API key");
			}
			else if (error != null)
			{
				cb.onResult(false, "Could not reach " + BASE_URL);
			}
			else
			{
				cb.onResult(false, "HTTP " + code);
			}
		});
	}

	/**
	 * GET /api/suggest for the items currently in the player's GE slots. Each entry
	 * of {@code items} is {@code {itemId, offerPrice}}; the offer price lets the
	 * server return the fill probability at that exact price. Results arrive by item
	 * id on an OkHttp thread. A single attempt — the periodic refresh is the natural
	 * retry, so a transient miss just shows briefly and clears on the next tick.
	 */
	public void fetchSuggestions(String apiKey, List<int[]> items, double risk, SuggestionsCallback cb)
	{
		if (apiKey == null || apiKey.trim().isEmpty())
		{
			cb.onError("API key not set");
			return;
		}
		if (items == null || items.isEmpty())
		{
			cb.onSuccess(Collections.emptyMap());
			return;
		}

		final StringBuilder itemsParam = new StringBuilder();
		for (final int[] it : items)
		{
			if (it == null || it.length == 0 || it[0] <= 0)
			{
				continue;
			}
			if (itemsParam.length() > 0)
			{
				itemsParam.append(',');
			}
			itemsParam.append(it[0]);
			if (it.length > 1 && it[1] > 0)
			{
				itemsParam.append(':').append(it[1]);
			}
		}
		if (itemsParam.length() == 0)
		{
			cb.onSuccess(Collections.emptyMap());
			return;
		}

		final HttpUrl endpoint = HttpUrl.parse(BASE_URL + "/api/suggest");
		if (endpoint == null)
		{
			cb.onError("Bad URL");
			return;
		}
		final double r = Math.max(0, Math.min(1, risk));
		final HttpUrl url = endpoint.newBuilder()
			.addQueryParameter("items", itemsParam.toString())
			.addQueryParameter("risk", String.format(Locale.US, "%.3f", r))
			.build();

		final Request request = new Request.Builder()
			.url(url)
			.header("Authorization", "Bearer " + apiKey.trim())
			.get()
			.build();

		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				cb.onError("Could not reach " + BASE_URL);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response res = response)
				{
					if (res.code() == 401)
					{
						cb.onError("Invalid API key");
						return;
					}
					if (!res.isSuccessful())
					{
						cb.onError("HTTP " + res.code());
						return;
					}
					final ResponseBody body = res.body();
					final String json = body == null ? null : body.string();
					final SuggestResponse decoded = json == null
						? null
						: gson.fromJson(json, SuggestResponse.class);
					final Map<Integer, Suggestion> byId = new HashMap<>();
					if (decoded != null && decoded.suggestions != null)
					{
						for (final Map.Entry<String, Suggestion> e : decoded.suggestions.entrySet())
						{
							try
							{
								byId.put(Integer.parseInt(e.getKey()), e.getValue());
							}
							catch (NumberFormatException ignored)
							{
								// Skip a non-numeric key; the server only emits ids.
							}
						}
					}
					cb.onSuccess(byId);
				}
				catch (IOException | JsonSyntaxException e)
				{
					cb.onError("Suggestions unavailable");
				}
			}
		});
	}

	/**
	 * Enqueue {@code request}, retrying transient failures (network errors and
	 * HTTP 5xx) with backoff. Success or any 4xx — a client error a retry cannot
	 * fix, such as a bad key — is definitive and ends the loop immediately;
	 * otherwise the loop ends only once every attempt is exhausted.
	 */
	private void send(Request request, TransportCallback cb)
	{
		attempt(request, cb, 1);
	}

	private void attempt(Request request, TransportCallback cb, int attemptNo)
	{
		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				retryOrComplete(request, cb, attemptNo, false, -1, e);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					final int code = r.code();
					// 2xx, or any 4xx (a bad request / invalid key a retry won't
					// fix), is definitive. Only 5xx and network failures — the
					// fingerprints of a cold or briefly-unreachable backend — retry.
					if (r.isSuccessful() || (code >= 400 && code < 500))
					{
						cb.onComplete(r.isSuccessful(), code, null);
					}
					else
					{
						retryOrComplete(request, cb, attemptNo, false, code, null);
					}
				}
			}
		});
	}

	private void retryOrComplete(Request request, TransportCallback cb, int attemptNo,
		boolean success, int code, IOException error)
	{
		if (attemptNo >= MAX_ATTEMPTS)
		{
			cb.onComplete(success, code, error);
			return;
		}
		final long delay = RETRY_BACKOFF_MS[attemptNo - 1];
		executor.schedule(() -> attempt(request, cb, attemptNo + 1), delay, TimeUnit.MILLISECONDS);
	}
}
