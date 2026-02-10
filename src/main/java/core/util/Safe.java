package core.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Callable;

public final class Safe {
    private static final Logger LOGGER = LoggerFactory.getLogger("core");

    private Safe() {}

    public static void run(String name, Runnable runnable) {
        try {
            runnable.run();
        } catch (Throwable t) {
            LOGGER.error("Unhandled exception in {}", name, t);
        }
    }

    public static <T> T call(String name, Callable<T> callable, T fallback) {
        try {
            return callable.call();
        } catch (Throwable t) {
            LOGGER.error("Unhandled exception in {}", name, t);
            return fallback;
        }
    }
}

