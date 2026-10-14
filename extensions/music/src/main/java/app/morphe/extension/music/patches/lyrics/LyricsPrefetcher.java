/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

import app.morphe.extension.music.patches.lyrics.model.Lyrics;
import app.morphe.extension.music.patches.lyrics.model.TrackInfo;
import app.morphe.extension.music.patches.lyrics.requests.LyricsProvider;
import app.morphe.extension.music.patches.lyrics.requests.LyricsRequests;
import app.morphe.extension.music.patches.lyrics.storage.LyricsCache;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Fetches the lyrics of the track queued behind the current one, so the switch to it
 * finds them in the cache instead of waiting for the network.
 *
 * <p>Runs on a thread of its own so it cannot delay or cancel the lookup of the track
 * actually playing: it never touches the request id, the in-flight fetches or the
 * state, and only ever adds to the cache.
 *
 * <p>Only what the lookup itself would store is written, so a prefetch never decides
 * which provider the user gets: the cache is keyed by provider, and a track no
 * provider answered for keeps its miss, which leaves the usual search running when the
 * track is reached.
 */
final class LyricsPrefetcher {

    private static final ExecutorService executor = Executors.newSingleThreadExecutor(
            new ThreadFactory() {
                @Override
                public Thread newThread(@NonNull Runnable runnable) {
                    Thread thread = new Thread(runnable, "LyricsPrefetch");
                    thread.setDaemon(true);
                    thread.setPriority(Thread.MIN_PRIORITY);
                    return thread;
                }
            });

    /**
     * Also the running task's generation: an older task stops at its next checkpoint
     * rather than fetching lyrics nobody asked for.
     */
    private static volatile String prefetchingFor;

    private static volatile Future<?> running;

    private LyricsPrefetcher() {
    }

    /**
     * Starts a prefetch for whatever plays after the given video. Cheap to call on every
     * track: an already-prefetched target is left alone and a hopeless one skipped.
     */
    static void prefetchSuccessorOf(@Nullable String currentVideoId) {
        if (currentVideoId == null || currentVideoId.isEmpty()) return;
        if (!Settings.LYRICS_ENABLED.get()) return;
        if (!Utils.isNetworkConnected()) return;

        final Future<?> previous;
        synchronized (LyricsPrefetcher.class) {
            if (currentVideoId.equals(prefetchingFor)) return;
            prefetchingFor = currentVideoId;
            previous = running;
        }
        if (previous != null) {
            // Frees the single thread for the track that is playing now.
            previous.cancel(true);
        }
        final Future<?> task = executor.submit(() -> runPrefetch(currentVideoId));
        synchronized (LyricsPrefetcher.class) {
            if (currentVideoId.equals(prefetchingFor)) {
                running = task;
            }
        }
    }

    private static void runPrefetch(String currentVideoId) {
        try {
            prefetch(currentVideoId);
        } catch (Exception ex) {
            Logger.printException(() -> "runPrefetch failure", ex);
        } finally {
            synchronized (LyricsPrefetcher.class) {
                if (currentVideoId.equals(prefetchingFor)) {
                    prefetchingFor = null;
                    running = null;
                }
            }
        }
    }

    private static void prefetch(String currentVideoId) {
        if (superseded(currentVideoId)) return;

        final String[] nextVideoId = new String[1];
        TrackInfo track = SessionQueue.nextTrackOf(currentVideoId, nextVideoId);
        if (superseded(currentVideoId)) return;
        if (track == null) return;

        // A successor named by the playing video is a repeat. One named only by title and
        // artist has no video id, which is fine: video-keyed providers are not asked.
        if (nextVideoId[0] != null && nextVideoId[0].equals(currentVideoId)) {
            return;
        }

        // Video-keyed providers only know the playing track, so ask the title ones.
        final List<LyricsProvider> providers = LyricsManager.searchableProviders(
                LyricsManager.providersInOrder(Settings.LYRICS_SOURCE.get()));
        if (providers.isEmpty()) return;
        if (allKnown(track, providers)) return;

        fetchIntoCache(currentVideoId, track, providers);
    }

    /** Provider order, stopping at the first usable answer. */
    private static void fetchIntoCache(String currentVideoId, TrackInfo track,
                                       List<LyricsProvider> providers) {
        for (LyricsProvider provider : providers) {
            if (superseded(currentVideoId)) return;

            // Custom terms belong to one track, never to another's default terms.
            if (LyricsRequests.isCustomMatchMode()) return;

            try {
                LyricsProvider.FetchResult result = provider.fetch(track);
                if (result == null || result.lyrics() == null
                        || result.lyrics() == Lyrics.NOT_FOUND) {
                    continue;
                }
                Lyrics lyrics = result.lyrics();
                if (!LyricsManager.isValidLyrics(lyrics, track)) continue;

                LyricsCache.put(track, LyricsManager.cacheSourceFor(lyrics), lyrics);
                Logger.printDebug(() -> "Lyrics prefetched: " + track + " via " + provider.name());
                return;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception ex) {
                Logger.printDebug(() -> "Lyrics prefetch failed: " + provider.name(), ex);
            }
        }
    }

    /** Whether every provider that could answer already has an answer stored. */
    private static boolean allKnown(TrackInfo track, List<LyricsProvider> providers) {
        for (LyricsProvider provider : providers) {
            if (LyricsCache.get(track, provider.name()) == null) return false;
        }
        return true;
    }

    private static boolean superseded(String currentVideoId) {
        return !currentVideoId.equals(prefetchingFor) || Thread.currentThread().isInterrupted();
    }
}