package com.searchableloottracker.wiki;

import java.io.File;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import javax.inject.Inject;
import net.runelite.client.RuneLite;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Resolves one complete source variant at a time and shares it between all item tooltips for that
 * source. Variant IDs mapped to the same Wiki context coalesce behind one future. Disk reads and
 * HTTP callbacks stay off the client thread.
 */
public final class WikiDropRateService
{
	private static final String LOOKUP_URL = "https://oldschool.runescape.wiki/w/Special:Lookup";
	private static final String USER_AGENT =
		"Loot Tracker Extended (https://github.com/Suffdev/loot-tracker-extended)";
	private static final int MAX_CONCURRENT_REQUESTS = 2;
	private static final int MAX_LOOKUPS_PER_SOURCE = 16;
	private static final int MAX_PENDING_REQUESTS = 64;
	private static final int MAX_CACHE_ENTRIES = 256;
	private static final int MAX_DISK_CACHE_ENTRIES = 1024;
	private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
	private final OkHttpClient httpClient;
	private final WikiDropTableDiskCache diskCache;
	// A source card can request several item tooltips at once. Coalescing here
	// prevents each item from downloading and parsing the same NPC page.
	private final Map<LookupKey, CompletableFuture<WikiDropTable>> inFlight = new ConcurrentHashMap<>();
	private final Set<Call> activeCalls = Collections.newSetFromMap(new ConcurrentHashMap<>());
	private final AtomicLong cacheGeneration = new AtomicLong();
	private final Map<LookupKey, WikiDropTable> cache = new LinkedHashMap<LookupKey, WikiDropTable>(
		MAX_CACHE_ENTRIES + 1, 0.75f, true)
	{
		@Override
		protected boolean removeEldestEntry(Map.Entry<LookupKey, WikiDropTable> eldest)
		{
			return size() > MAX_CACHE_ENTRIES;
		}
	};
	private final Queue<PendingLookup> pendingLookups = new ArrayDeque<>();
	private int activeRequests;
	// Network-backed features must remain inert until the user explicitly opts in.
	private volatile boolean enabled;

	@Inject
	public WikiDropRateService(OkHttpClient httpClient)
	{
		this(httpClient, new WikiDropTableDiskCache(
			new File(new File(RuneLite.RUNELITE_DIR, "loot-tracker-extended"), "wiki-cache"),
			MAX_DISK_CACHE_ENTRIES));
	}

	WikiDropRateService(OkHttpClient httpClient, WikiDropTableDiskCache diskCache)
	{
		this.httpClient = httpClient;
		this.diskCache = diskCache;
	}

	public CompletableFuture<WikiDropTable> lookup(String sourceType, String sourceName, Integer npcId)
	{
		if (!enabled)
		{
			return failedLookup("Wiki drop rates are disabled");
		}
		if (!canLookup(sourceType, sourceName))
		{
			return failedLookup("Wiki lookup is not permitted for this loot source");
		}
		WikiSourceResolution resolution = WikiSourceResolver.resolve(sourceType, sourceName, npcId);
		return lookup(resolution);
	}

	private CompletableFuture<WikiDropTable> lookup(WikiSourceResolution resolution)
	{
		String lookupName = resolution.getPageTitle();
		Integer lookupNpcId = resolution.getNpcId();
		LookupKey cacheKey = new LookupKey(
			lookupName, lookupNpcId, resolution.getTableContext());
		WikiDropTable cached = getCached(cacheKey);
		if (cached != null)
		{
			return CompletableFuture.completedFuture(cached);
		}

		CompletableFuture<WikiDropTable> result = new CompletableFuture<>();
		long generation = cacheGeneration.get();
		CompletableFuture<WikiDropTable> existing = inFlight.putIfAbsent(cacheKey, result);
		if (existing != null)
		{
			if (existing.isCompletedExceptionally() && inFlight.remove(cacheKey, existing))
			{
				return lookup(resolution);
			}
			return existing;
		}

		result.whenComplete((table, error) ->
		{
			// A cache clear/disable increments the generation so late HTTP
			// completions cannot silently repopulate an invalidated cache.
			if (error == null && generation == cacheGeneration.get())
			{
				cache(cacheKey, table);
			}
			// Publish a successful value to memory before removing the in-flight marker. A caller
			// released by CompletableFuture completion can otherwise slip between those operations
			// and start a duplicate request.
			inFlight.remove(cacheKey, result);
			if (error == null && generation == cacheGeneration.get())
			{
				diskCache.put(cacheKey.persistentKey(), table);
			}
		});
		// Recheck after registering inFlight: another completion may have
		// populated memory between the first cache read and putIfAbsent.
		cached = getCached(cacheKey);
		if (cached != null)
		{
			result.complete(cached);
		}
		else
		{
			httpClient.dispatcher().executorService().execute(() ->
			{
				if (!enabled || generation != cacheGeneration.get())
				{
					result.completeExceptionally(new CancellationException("Wiki drop-rate request invalidated"));
					return;
				}
				WikiDropTable persisted = diskCache.get(cacheKey.persistentKey());
				if (!enabled || generation != cacheGeneration.get())
				{
					result.completeExceptionally(new CancellationException("Wiki drop-rate request invalidated"));
					return;
				}
				if (persisted != null)
				{
					result.complete(persisted);
				}
				else
				{
					enqueue(new PendingLookup(lookupName, lookupNpcId,
						resolution.getTableContext(), generation, result));
				}
			});
		}
		return result;
	}

	/** Resolves and combines every concrete variant represented by a name-grouped loot source. */
	public CompletableFuture<WikiDropTable> lookupVariants(String sourceType, String sourceName,
		Set<Integer> npcIds)
	{
		return lookupVariants(sourceType, sourceName, npcIds, npcIds == null || npcIds.isEmpty());
	}

	/**
	 * Resolves known variants plus broad candidates when older kills in the aggregate have no ID.
	 * Plans are deduplicated before any futures are created and fall back to bounded name lookup if
	 * the source contains an unreasonable number of distinct variants.
	 */
	public CompletableFuture<WikiDropTable> lookupVariants(String sourceType, String sourceName,
		Set<Integer> npcIds, boolean hasUnknownNpcVariants)
	{
		if (!enabled || !canLookup(sourceType, sourceName))
		{
			return lookup(sourceType, sourceName, (Integer) null);
		}

		Map<LookupKey, WikiSourceResolution> plans = new LinkedHashMap<>();
		Map<String, Boolean> broadPages = new LinkedHashMap<>();
		if (hasUnknownNpcVariants || npcIds == null || npcIds.isEmpty())
		{
			addNameCandidates(sourceType, sourceName, plans, broadPages);
		}

		if (npcIds != null)
		{
			for (Integer npcId : npcIds)
			{
				WikiSourceResolution resolution = WikiSourceResolver.resolve(sourceType, sourceName, npcId);
				if (!broadPages.containsKey(normalizePage(resolution.getPageTitle())))
				{
					plans.putIfAbsent(keyFor(resolution), resolution);
				}
				if (plans.size() > MAX_LOOKUPS_PER_SOURCE)
				{
					plans.clear();
					broadPages.clear();
					addNameCandidates(sourceType, sourceName, plans, broadPages);
					break;
				}
			}
		}
		return executePlans(new ArrayList<>(plans.values()));
	}

	private static void addNameCandidates(String sourceType, String sourceName,
		Map<LookupKey, WikiSourceResolution> plans, Map<String, Boolean> broadPages)
	{
		for (WikiSourceResolution candidate :
			WikiSourceResolver.resolveNameCandidates(sourceType, sourceName))
		{
			plans.putIfAbsent(keyFor(candidate), candidate);
			broadPages.put(normalizePage(candidate.getPageTitle()), Boolean.TRUE);
		}
	}

	private CompletableFuture<WikiDropTable> executePlans(List<WikiSourceResolution> plans)
	{
		if (plans.isEmpty())
		{
			return failedLookup("No Wiki lookup plan is available for this source");
		}
		Map<String, Boolean> pages = new LinkedHashMap<>();
		List<CompletableFuture<LookupResult>> attempts = new ArrayList<>(plans.size());
		for (WikiSourceResolution plan : plans)
		{
			pages.put(normalizePage(plan.getPageTitle()), Boolean.TRUE);
			attempts.add(lookup(plan).handle(LookupResult::new));
		}
		boolean labelPages = pages.size() > 1;
		CompletableFuture<?>[] futures = attempts.toArray(new CompletableFuture<?>[0]);
		return CompletableFuture.allOf(futures).thenApply(ignored ->
		{
			List<WikiDropTable> tables = new ArrayList<>(attempts.size());
			Throwable firstFailure = null;
			for (int index = 0; index < attempts.size(); index++)
			{
				LookupResult attempt = attempts.get(index).join();
				if (attempt.table != null)
				{
					tables.add(labelPages ? attempt.table.withContextPrefix(
						plans.get(index).getPageTitle()) : attempt.table);
				}
				else if (firstFailure == null)
				{
					firstFailure = attempt.error;
				}
			}
			if (tables.isEmpty())
			{
				throw new CompletionException(firstFailure == null
					? new IOException("All Wiki lookups failed") : firstFailure);
			}
			return WikiDropTable.merge(tables);
		});
	}

	private static LookupKey keyFor(WikiSourceResolution resolution)
	{
		return new LookupKey(resolution.getPageTitle(), resolution.getNpcId(),
			resolution.getTableContext());
	}

	private static String normalizePage(String pageTitle)
	{
		return pageTitle.trim().toLowerCase(Locale.ENGLISH);
	}

	private static CompletableFuture<WikiDropTable> failedLookup(String message)
	{
		CompletableFuture<WikiDropTable> failed = new CompletableFuture<>();
		failed.completeExceptionally(new CancellationException(message));
		return failed;
	}

	/**
	 * Allows only RuneLite source categories whose names identify game content. In particular,
	 * PLAYER records contain the other player's display name and must never reach the Wiki.
	 */
	public boolean canLookup(String sourceType, String sourceName)
	{
		if (sourceType == null || sourceName == null || sourceName.trim().isEmpty())
		{
			return false;
		}
		switch (sourceType.trim().toUpperCase(Locale.ENGLISH))
		{
			case "NPC":
			case "PICKPOCKET":
			case "EVENT":
				return true;
			default:
				return false;
		}
	}

	private WikiDropTable getCached(LookupKey key)
	{
		synchronized (cache)
		{
			return cache.get(key);
		}
	}

	private void cache(LookupKey key, WikiDropTable table)
	{
		synchronized (cache)
		{
			cache.put(key, table);
		}
	}

	public void setEnabled(boolean enabled)
	{
		this.enabled = enabled;
		if (!enabled)
		{
			invalidateRequests();
		}
	}

	public void clearCache()
	{
		invalidateRequests();
		synchronized (cache)
		{
			cache.clear();
		}
		diskCache.clear();
	}

	private void invalidateRequests()
	{
		cacheGeneration.incrementAndGet();
		CancellationException cancelled = new CancellationException("Wiki drop-rate request invalidated");
		synchronized (this)
		{
			PendingLookup pending;
			while ((pending = pendingLookups.poll()) != null)
			{
				pending.result.completeExceptionally(cancelled);
			}
		}
		for (Call call : activeCalls)
		{
			call.cancel();
		}
	}

	int cachedEntryCount()
	{
		synchronized (cache)
		{
			return cache.size();
		}
	}

	public String getDropTableUrl(String sourceType, String sourceName, Integer npcId)
	{
		if (!enabled || !canLookup(sourceType, sourceName))
		{
			throw new IllegalStateException("Wiki lookup is disabled or not permitted for this loot source");
		}
		WikiSourceResolution resolution = WikiSourceResolver.resolve(sourceType, sourceName, npcId);
		String lookupName = resolution.getPageTitle();
		Integer lookupNpcId = resolution.getNpcId();
		return buildUrl(lookupName, lookupNpcId).newBuilder()
			.fragment(resolution.getSection())
			.build()
			.toString();
	}

	public String getDropTableUrlForVariants(String sourceType, String sourceName, Set<Integer> npcIds)
	{
		if (npcIds == null || npcIds.isEmpty())
		{
			return getDropTableUrl(sourceType, sourceName, (Integer) null);
		}
		Integer representative = npcIds.iterator().next();
		WikiSourceResolution first = WikiSourceResolver.resolve(sourceType, sourceName, representative);
		boolean mixedContexts = false;
		for (Integer npcId : npcIds)
		{
			WikiSourceResolution candidate = WikiSourceResolver.resolve(sourceType, sourceName, npcId);
			if (!first.getPageTitle().equals(candidate.getPageTitle()))
			{
				return getDropTableUrl(sourceType, sourceName, (Integer) null);
			}
			mixedContexts |= !first.getSection().equals(candidate.getSection())
				|| !Objects.equals(first.getTableContext(), candidate.getTableContext());
		}
		if (mixedContexts)
		{
			return buildUrl(first.getPageTitle(), null).toString();
		}
		return getDropTableUrl(sourceType, sourceName, representative);
	}

	static String resolveLookupName(String sourceName)
	{
		return WikiSourceResolver.resolve("NPC", sourceName, null).getPageTitle();
	}

	private synchronized void enqueue(PendingLookup lookup)
	{
		if (!enabled || lookup.generation != cacheGeneration.get())
		{
			lookup.result.completeExceptionally(new CancellationException("Wiki drop-rate request invalidated"));
			return;
		}
		if (pendingLookups.size() >= MAX_PENDING_REQUESTS)
		{
			lookup.result.completeExceptionally(new IOException("Wiki drop-rate request queue is full"));
			return;
		}
		pendingLookups.add(lookup);
		startPendingRequests();
	}

	private void startPendingRequests()
	{
		// Keep hover bursts polite and bounded without blocking OkHttp's worker.
		while (activeRequests < MAX_CONCURRENT_REQUESTS && !pendingLookups.isEmpty())
		{
			PendingLookup lookup = pendingLookups.remove();
			activeRequests++;
			request(lookup, lookup.npcId);
		}
	}

	private void request(PendingLookup lookup, Integer npcId)
	{
		Request request = new Request.Builder()
			.url(buildUrl(lookup.sourceName, npcId))
			.header("User-Agent", USER_AGENT)
			.build();
		Call requestCall = httpClient.newCall(request);
		activeCalls.add(requestCall);
		requestCall.enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException exception)
			{
				activeCalls.remove(call);
				lookup.result.completeExceptionally(exception);
				finishRequest();
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				activeCalls.remove(call);
				try (Response closedResponse = response)
				{
					if (!enabled || lookup.generation != cacheGeneration.get())
					{
						lookup.result.completeExceptionally(
							new CancellationException("Wiki drop-rate request invalidated"));
						finishRequest();
						return;
					}
					ResponseBody body = closedResponse.body();
					if (!closedResponse.isSuccessful() || body == null)
					{
						if (npcId != null)
						{
							request(lookup, null);
							return;
						}
						lookup.result.completeExceptionally(
							new IOException("OSRS Wiki returned HTTP " + closedResponse.code()));
						finishRequest();
						return;
					}

					WikiDropTable table = WikiDropTableParser.parse(
						new ByteArrayInputStream(readBoundedBody(body)));
					if (npcId != null || lookup.tableContext != null)
					{
						String selectedContext = lookup.tableContext;
						if (!table.hasContext(selectedContext))
						{
							selectedContext = contextFromFragment(closedResponse.request().url().fragment());
						}
						if (table.hasContext(selectedContext))
						{
							table = table.selectContext(selectedContext);
						}
					}
					// NPC IDs disambiguate variants when possible. Historical records may
					// lack an ID, and some Wiki pages only resolve through their name.
					if (table.isEmpty() && npcId != null)
					{
						request(lookup, null);
						return;
					}
					if (table.isEmpty())
					{
						lookup.result.completeExceptionally(
							new IOException("OSRS Wiki page has no resolvable drop table"));
						finishRequest();
						return;
					}
					lookup.result.complete(table);
					finishRequest();
				}
				catch (Exception exception)
				{
					lookup.result.completeExceptionally(exception);
					finishRequest();
				}
			}
		});
	}

	private static byte[] readBoundedBody(ResponseBody body) throws IOException
	{
		long declaredLength = body.contentLength();
		if (declaredLength > MAX_RESPONSE_BYTES)
		{
			throw new IOException("OSRS Wiki response exceeds the 4 MiB limit");
		}

		try (InputStream input = body.byteStream();
			ByteArrayOutputStream output = new ByteArrayOutputStream(
				declaredLength > 0 ? (int) declaredLength : 8192))
		{
			byte[] buffer = new byte[8192];
			int total = 0;
			int read;
			while ((read = input.read(buffer)) != -1)
			{
				total += read;
				if (total > MAX_RESPONSE_BYTES)
				{
					throw new IOException("OSRS Wiki response exceeds the 4 MiB limit");
				}
				output.write(buffer, 0, read);
			}
			return output.toByteArray();
		}
	}

	private synchronized void finishRequest()
	{
		activeRequests--;
		startPendingRequests();
	}

	static HttpUrl buildUrl(String sourceName, Integer npcId)
	{
		HttpUrl.Builder builder = HttpUrl.get(LOOKUP_URL).newBuilder()
			.addQueryParameter("type", "npc");
		if (npcId != null)
		{
			builder.addQueryParameter("id", Integer.toString(npcId));
		}
		return builder.addQueryParameter("name", sourceName).build();
	}

	private static String contextFromFragment(String fragment)
	{
		return fragment == null ? null : fragment.replace('_', ' ').trim();
	}

	private static final class LookupKey
	{
		private final String sourceName;
		private final String variant;

		private LookupKey(String sourceName, Integer npcId, String tableContext)
		{
			this.sourceName = sourceName.trim().toLowerCase(Locale.ENGLISH);
			this.variant = tableContext != null && !tableContext.trim().isEmpty()
				? "context:" + tableContext.trim().toLowerCase(Locale.ENGLISH)
				: npcId == null ? "" : "id:" + npcId;
		}

		@Override
		public boolean equals(Object other)
		{
			if (this == other)
			{
				return true;
			}
			if (!(other instanceof LookupKey))
			{
				return false;
			}
			LookupKey key = (LookupKey) other;
			return sourceName.equals(key.sourceName) && variant.equals(key.variant);
		}

		@Override
		public int hashCode()
		{
			return 31 * sourceName.hashCode() + variant.hashCode();
		}

		private String persistentKey()
		{
			return sourceName + '\0' + variant;
		}
	}

	private static final class PendingLookup
	{
		private final String sourceName;
		private final Integer npcId;
		private final String tableContext;
		private final long generation;
		private final CompletableFuture<WikiDropTable> result;

		private PendingLookup(String sourceName, Integer npcId, String tableContext, long generation,
			CompletableFuture<WikiDropTable> result)
		{
			this.sourceName = sourceName;
			this.npcId = npcId;
			this.tableContext = tableContext;
			this.generation = generation;
			this.result = result;
		}
	}

	private static final class LookupResult
	{
		private final WikiDropTable table;
		private final Throwable error;

		private LookupResult(WikiDropTable table, Throwable error)
		{
			this.table = table;
			this.error = error;
		}
	}
}
