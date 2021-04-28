package com.io7m.aeron_guide.take3;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

/**
 * The default implementation of the {@link EchoServerExecutorService}
 * interface.
 */
public final class EchoServerExecutor implements EchoServerExecutorService {

    private static final Logger LOG
            = LoggerFactory.getLogger(EchoServerExecutor.class);

    private final ExecutorService executor;

    private EchoServerExecutor(
            final ExecutorService in_exec) {
        this.executor = Objects.requireNonNull(in_exec, "exec");
    }

    @Override
    public boolean isExecutorThread() {
        return Thread.currentThread() instanceof EchoServerThread;
    }

    @Override
    public Future<Integer> my_call(final Callable<Integer> callable) {
        Objects.requireNonNull(callable, "callable");

        return this.executor.submit(() -> { // Dimon: Runnable tutorial: https://knpcode.com/java/concurrency/java-executor-tutorial-executorservice-scheduledexecutorservice/  submit() returns Future<?>
            
            // TODO: figure out why we can't use try-catch block here. Afterall "callable.call()" can throw and Exception.
            return callable.call();
            
//            try {
////        LOG.info("+++ executor called on thread id: " + Thread.currentThread().getId() + ", thread name: " + Thread.currentThread().getName());
//                // Return received_fragments_count
//                //return callable.call();
//                return 1;
//            } catch (final Throwable e) {
//                LOG.error("uncaught exception: ", e);
//            }
        });
    }

    @Override
    public void close() {
        this.executor.shutdown();
    }

    private static final class EchoServerThread extends Thread {

        EchoServerThread(final Runnable target) {
            super(Objects.requireNonNull(target, "target"));
        }
    }

    /**
     * @return A new executor
     */
    public static EchoServerExecutor create() {
        final ThreadFactory factory = r -> {
            final EchoServerThread t = new EchoServerThread(r);
            t.setName(new StringBuilder(64)
                    .append("com.io7m.aeron_guide.take3.server[")
                    .append(Long.toUnsignedString(t.getId()))
                    .append("]")
                    .toString());
            return t;
        };

        return new EchoServerExecutor(Executors.newSingleThreadExecutor(factory));
    }
}
