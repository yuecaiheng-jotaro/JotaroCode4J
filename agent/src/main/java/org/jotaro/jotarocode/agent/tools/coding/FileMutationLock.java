package org.jotaro.jotarocode.agent.tools.coding;

import java.nio.file.Path;

/**
 * 文件写操作分段锁（Striped Lock）。
 *
 * <p>将规范化绝对路径的哈希值映射到固定 64 个同步监视器对象上：
 * 不同文件大概率落入不同锁槽从而支持并行写入；针对同一个文件的并发 {@code write} 和 {@code edit} 则严格串行化，
 * 避免多线程同时读写同一文件造成内容撕裂或覆盖丢失。
 */
final class FileMutationLock {

    /** 固定 64 个分段锁槽。 */
    private static final Object[] LOCKS = new Object[64];

    static {
        for (int index = 0; index < LOCKS.length; index++) {
            LOCKS[index] = new Object();
        }
    }

    private FileMutationLock() {
    }

    /**
     * 根据目标文件路径获取对应的同步锁对象。
     *
     * @param path 目标文件路径
     * @return 该路径所属槽位的锁对象
     */
    static Object of(Path path) {
        int hash = path.toAbsolutePath().normalize().hashCode() & Integer.MAX_VALUE;
        return LOCKS[hash % LOCKS.length];
    }
}
