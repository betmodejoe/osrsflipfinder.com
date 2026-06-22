package com.osrsflipfinder;

import com.google.gson.Gson;
import java.io.IOException;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Thin async wrapper over RuneLite's shared OkHttp client for talking to the
 * OSRS Flip Finder ingest API. All calls are non-blocking (OkHttp's own
 * dispatcher), so they're safe to invoke from the client thread.
 */
@Slf4j
@Singleton
public class FlipFinderApiClient
{
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

	private final OkHttpClient okHttpClient;
	private final Gson gson;

	@Inject
	private FlipFinderApiClient(OkHttpClient okHttpClient, Gson gson)
	{
		this.okHttpClient = okHttpClient;
		this.gson = gson;
	}

	/** Result of an API call, delivered on an OkHttp background thread. */
	public interface ResultCallback
	{
		void onResult(boolean ok, String message);
	}

	private static String normalizeBase(String baseUrl)
	{
		String b = baseUrl == null ? "" : baseUrl.trim();
		while (b.endsWith("/"))
		{
			b = b.substring(0, b.length() - 1);
		}
		return b;
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

	/** POST a batch of GE updates to /api/sync/trades. */
	public void submit(String baseUrl, String apiKey, List<GeSyncTx> fills, ResultCallback cb)
	{
		final String base = normalizeBase(baseUrl);
		if (base.isEmpty() || apiKey == null || apiKey.trim().isEmpty())
		{
			cb.onResult(false, "Base URL or API key not set");
			return;
		}

		final String json = gson.toJson(new Payload(fills));
		final Request request = new Request.Builder()
			.url(base + "/api/sync/trades")
			.header("Authorization", "Bearer " + apiKey.trim())
			.post(RequestBody.create(JSON, json))
			.build();

		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.warn("Flip Finder sync request failed", e);
				cb.onResult(false, e.getMessage());
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					cb.onResult(r.isSuccessful(), "HTTP " + r.code());
				}
			}
		});
	}

	/** GET /api/sync/me to verify the key (the "Test connection" button). */
	public void testConnection(String baseUrl, String apiKey, ResultCallback cb)
	{
		final String base = normalizeBase(baseUrl);
		if (base.isEmpty() || apiKey == null || apiKey.trim().isEmpty())
		{
			cb.onResult(false, "Set the Base URL and API key first");
			return;
		}

		final Request request = new Request.Builder()
			.url(base + "/api/sync/me")
			.header("Authorization", "Bearer " + apiKey.trim())
			.get()
			.build();

		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				cb.onResult(false, "Could not reach " + base);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					if (r.isSuccessful())
					{
						cb.onResult(true, "Connected");
					}
					else if (r.code() == 401)
					{
						cb.onResult(false, "Invalid API key");
					}
					else
					{
						cb.onResult(false, "HTTP " + r.code());
					}
				}
			}
		});
	}
}
