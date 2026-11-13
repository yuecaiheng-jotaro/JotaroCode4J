package org.jotaro.jotarocode.agent.tools.coding;

import java.nio.file.Path;

/**
 * 按路径哈希分到 64 把锁里的一把。不同文件大多并行，同一文件的 write 和 edit 串行。
 * 这不是公平锁，只避免两个线程同时改写同一路径。
 */
final class FileMutationLock {

    private static final Object[] LOCKS = new Object[64];

    static {
        for (int index = 0; index < LOCKS.length; index++) {
            LOCKS[index] = new Object();
        }
    }

    private FileMutationLock() {
    }

    static Object of(Path path) {
        int hash = path.toAbsolutePath().normalize().hashCode() & Integer.MAX_VALUE;
        return LOCKS[hash % LOCKS.length];
    }
}
