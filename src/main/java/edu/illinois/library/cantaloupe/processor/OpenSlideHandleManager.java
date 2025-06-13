package edu.illinois.library.cantaloupe.processor;

import org.openslide.OpenSlide;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Manages a cache/pool of OpenSlide instances, allowing them to be reused
 * across multiple requests to the same source file.
 */
public final class OpenSlideHandleManager {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(OpenSlideHandleManager.class);

    // Using ConcurrentHashMap for thread-safe access
    // Stores OpenSlide instances, keyed by their source file Path
    private static final ConcurrentHashMap<Path, OpenSlide> activeHandles = new ConcurrentHashMap<>();

    // Optional: For idle cleanup
    private static final ConcurrentHashMap<Path, Long> lastAccessedTime = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor();
    private static final long IDLE_TIMEOUT_SECONDS = 3600; // 1 hour idle timeout

    static {
        // Schedule a cleanup task to run periodically
        scheduler.scheduleAtFixedRate(
            OpenSlideHandleManager::cleanupIdleHandles,
            IDLE_TIMEOUT_SECONDS, // Initial delay
            IDLE_TIMEOUT_SECONDS / 2, // Run every half of the idle timeout
            TimeUnit.SECONDS
        );

        // Add a shutdown hook to close all handles cleanly on application exit
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOGGER.info("Shutting down OpenSlideHandleManager and closing all active handles.");
            closeAllHandles();
            scheduler.shutdownNow();
        }));
    }

    private OpenSlideHandleManager() {
        // Private constructor to prevent instantiation
    }

    /**
     * Retrieves an OpenSlide instance for the given source file.
     * If an instance for this file already exists in the pool, it is reused.
     * Otherwise, a new instance is created and added to the pool.
     *
     * @param sourceFile The path to the OpenSlide file.
     * @return An OpenSlide instance.
     * @throws IOException If the OpenSlide file cannot be opened.
     */
    public static OpenSlide getOrCreateHandle(Path sourceFile) throws IOException {
        // Use computeIfAbsent for atomic check-then-create
        OpenSlide handle = activeHandles.computeIfAbsent(sourceFile, path -> {
            try {
                LOGGER.info("Opening new OpenSlide handle for: {}", path);
                return new OpenSlide(path.toFile());
            } catch (IOException e) {
                LOGGER.error("Failed to open OpenSlide file: {}", path, e);
                // Re-throw as unchecked to be caught by the caller
                throw new UncheckedIOException(e);
            }
        });
        lastAccessedTime.put(sourceFile, System.currentTimeMillis());
        return handle;
    }

    /**
     * Signals that a handle is no longer actively being used.
     * This manager keeps track of last access time for idle cleanup.
     * This method does *not* immediately close the handle.
     *
     * @param sourceFile The path to the OpenSlide file.
     */
    public static void releaseHandle(Path sourceFile) {
        // Update last accessed time, if the handle is still active
        if (activeHandles.containsKey(sourceFile)) {
            lastAccessedTime.put(sourceFile, System.currentTimeMillis());
            LOGGER.debug("Released OpenSlide handle for: {}", sourceFile);
        }
    }

    /**
     * Closes a specific OpenSlide handle and removes it from the pool.
     * This should generally only be called by the cleanup task or if a handle
     * becomes invalid.
     *
     * @param sourceFile The path to the OpenSlide file associated with the handle.
     */
    public static void closeHandle(Path sourceFile) {
        OpenSlide handle = activeHandles.remove(sourceFile);
        if (handle != null) {
            handle.close();
            lastAccessedTime.remove(sourceFile);
            LOGGER.info("Closed OpenSlide handle for: {}", sourceFile);
        }
    }

    /**
     * Cleans up idle OpenSlide handles from the pool.
     */
    private static void cleanupIdleHandles() {
        long currentTime = System.currentTimeMillis();
        for (Path sourceFile : lastAccessedTime.keySet()) {
            Long lastAccess = lastAccessedTime.get(sourceFile);
            if (lastAccess != null &&
                (currentTime - lastAccess) > (IDLE_TIMEOUT_SECONDS * 1000L)) {
                LOGGER.info("Closing idle OpenSlide handle for: {} (last accessed {} seconds ago)",
                            sourceFile, (currentTime - lastAccess) / 1000);
                closeHandle(sourceFile);
            }
        }
    }

    /**
     * Closes all OpenSlide handles in the pool. Called on application shutdown.
     */
    public static void closeAllHandles() {
        for (Path sourceFile : activeHandles.keySet()) {
            closeHandle(sourceFile);
        }
        activeHandles.clear();
        lastAccessedTime.clear();
    }
}