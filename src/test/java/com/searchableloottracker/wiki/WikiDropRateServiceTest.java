package com.searchableloottracker.wiki;

import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;
import java.util.List;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

public class WikiDropRateServiceTest
{
	private static final String DROP_TABLE =
		"<table class='item-drops'><tr><th>Item</th><th>Quantity</th><th>Rarity</th></tr>"
			+ "<tr><td>Coins</td><td>10</td><td>1/2</td></tr></table>";

	private OkHttpClient client;

	@After
	public void shutDownClient()
	{
		if (client != null)
		{
			client.dispatcher().executorService().shutdownNow();
			client.connectionPool().evictAll();
		}
	}

	@Test
	public void cachesByNpcIdAndNameOnlySeparately() throws Exception
	{
		AtomicInteger requests = new AtomicInteger();
		List<String> requestedUrls = new ArrayList<>();
		WikiDropRateService service = service(chain ->
		{
			requests.incrementAndGet();
			requestedUrls.add(chain.request().url().toString());
			return response(chain, 200, DROP_TABLE);
		});

		service.lookup("NPC", "Gnome", 66).get(5, TimeUnit.SECONDS);
		service.lookup("NPC", "Gnome", 66).get(5, TimeUnit.SECONDS);
		service.lookup("NPC", "Gnome", 67).get(5, TimeUnit.SECONDS);
		service.lookup("NPC", "Gnome", null).get(5, TimeUnit.SECONDS);

		assertEquals(requestedUrls.toString(), 3, requests.get());
	}

	@Test
	public void failedLookupCanBeRetried() throws Exception
	{
		AtomicInteger requests = new AtomicInteger();
		WikiDropRateService service = service(chain ->
		{
			if (requests.getAndIncrement() == 0)
			{
				throw new IOException("temporary failure");
			}
			return response(chain, 200, DROP_TABLE);
		});

		try
		{
			service.lookup("NPC", "Guard", 1).get(5, TimeUnit.SECONDS);
			fail("Expected the first lookup to fail");
		}
		catch (ExecutionException expected)
		{
			// Retrying is the behavior under test.
		}

		service.lookup("NPC", "Guard", 1).get(5, TimeUnit.SECONDS);
		assertEquals(2, requests.get());
	}

	@Test
	public void completedCacheIsBounded() throws Exception
	{
		WikiDropRateService service = service(chain -> response(chain, 200, DROP_TABLE));
		for (int index = 0; index < 260; index++)
		{
			service.lookup("EVENT", "Source " + index, null).get(5, TimeUnit.SECONDS);
		}

		assertEquals(256, service.cachedEntryCount());
	}

	@Test
	public void clearingTheCacheForcesARefetch() throws Exception
	{
		AtomicInteger requests = new AtomicInteger();
		WikiDropRateService service = service(chain ->
		{
			requests.incrementAndGet();
			return response(chain, 200, DROP_TABLE);
		});

		service.lookup("NPC", "Guard", 1).get(5, TimeUnit.SECONDS);
		service.clearCache();
		service.lookup("NPC", "Guard", 1).get(5, TimeUnit.SECONDS);

		assertEquals(2, requests.get());
	}

	@Test
	public void disablingDropRatesPreventsRequests() throws Exception
	{
		AtomicInteger requests = new AtomicInteger();
		WikiDropRateService service = service(chain ->
		{
			requests.incrementAndGet();
			return response(chain, 200, DROP_TABLE);
		});
		service.setEnabled(false);

		try
		{
			service.lookup("NPC", "Guard", 1).get(5, TimeUnit.SECONDS);
			fail("Expected disabled lookups to fail without requesting the Wiki");
		}
		catch (CancellationException | ExecutionException expected)
		{
			// No HTTP request is the behavior under test.
		}

		assertEquals(0, requests.get());
	}

	@Test
	public void defaultsToDisabled() throws Exception
	{
		AtomicInteger requests = new AtomicInteger();
		WikiDropRateService service = disabledService(chain ->
		{
			requests.incrementAndGet();
			return response(chain, 200, DROP_TABLE);
		});

		assertLookupFails(service, "NPC", "Guard");
		assertEquals(0, requests.get());
	}

	@Test
	public void rejectsPlayerAndUnknownSourcesBeforeHttp() throws Exception
	{
		AtomicInteger requests = new AtomicInteger();
		WikiDropRateService service = service(chain ->
		{
			requests.incrementAndGet();
			return response(chain, 200, DROP_TABLE);
		});

		assertLookupFails(service, "PLAYER", "Some Player");
		assertLookupFails(service, "OTHER", "Some Player");
		assertEquals(0, requests.get());
		assertEquals(false, service.canLookup("PLAYER", "Some Player"));
		assertEquals(true, service.canLookup("NPC", "Guard"));
		assertEquals(true, service.canLookup("PICKPOCKET", "Guard"));
		assertEquals(true, service.canLookup("EVENT", "Barrows"));
	}

	@Test
	public void doesNotSetACustomUserAgent() throws Exception
	{
		WikiDropRateService service = service(chain ->
		{
			assertNull(chain.request().header("User-Agent"));
			return response(chain, 200, DROP_TABLE);
		});

		service.lookup("NPC", "Guard", 1).get(5, TimeUnit.SECONDS);
	}

	@Test(expected = IllegalStateException.class)
	public void rejectsPlayerWikiUrls()
	{
		WikiDropRateService service = service(chain -> response(chain, 200, DROP_TABLE));
		service.getDropTableUrl("PLAYER", "Some Player", null);
	}

	@Test
	public void rejectsDeclaredOversizedResponses() throws Exception
	{
		WikiDropRateService service = service(chain -> response(chain, 200, oversizedBody()));
		assertLookupFails(service, "NPC", "Guard");
	}

	@Test
	public void rejectsChunkedOversizedResponses() throws Exception
	{
		String body = oversizedBody();
		WikiDropRateService service = service(chain -> new Response.Builder()
			.request(chain.request())
			.protocol(Protocol.HTTP_1_1)
			.code(200)
			.message("Test response")
			.body(new ResponseBody()
			{
				@Override
				public MediaType contentType()
				{
					return MediaType.parse("text/html; charset=utf-8");
				}

				@Override
				public long contentLength()
				{
					return -1;
				}

				@Override
				public Buffer source()
				{
					return new Buffer().writeUtf8(body);
				}
			})
			.build());

		assertLookupFails(service, "NPC", "Guard");
	}

	private static void assertLookupFails(WikiDropRateService service, String type, String name)
		throws Exception
	{
		try
		{
			service.lookup(type, name, 1).get(5, TimeUnit.SECONDS);
			fail("Expected Wiki lookup to fail");
		}
		catch (CancellationException | ExecutionException expected)
		{
			// Failure before a usable parsed response is the behavior under test.
		}
	}

	private static String oversizedBody()
	{
		StringBuilder body = new StringBuilder(4 * 1024 * 1024 + 1);
		while (body.length() <= 4 * 1024 * 1024)
		{
			body.append('x');
		}
		return body.toString();
	}

	private WikiDropRateService service(Interceptor interceptor)
	{
		WikiDropRateService service = disabledService(interceptor);
		service.setEnabled(true);
		return service;
	}

	private WikiDropRateService disabledService(Interceptor interceptor)
	{
		client = new OkHttpClient.Builder().addInterceptor(interceptor).build();
		return new WikiDropRateService(client, new WikiDropTableDiskCache(null, 0));
	}

	private static Response response(Interceptor.Chain chain, int code, String body)
	{
		return new Response.Builder()
			.request(chain.request())
			.protocol(Protocol.HTTP_1_1)
			.code(code)
			.message("Test response")
			.body(ResponseBody.create(MediaType.parse("text/html; charset=utf-8"), body))
			.build();
	}
}
