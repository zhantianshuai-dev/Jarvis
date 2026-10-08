package com.zhan.jarvis.concurrency;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 固定容量的分段锁。相同业务键落到同一把锁，不会因用户或会话增长而积累锁对象。
 */
public final class StripedLock {

    private final Lock[] locks;

    public StripedLock(int stripes) {
        int size = Math.max(1, stripes);
        this.locks = new Lock[size];
        for (int i = 0; i < size; i++) {
            locks[i] = new ReentrantLock();
        }
    }

    public Lock forKey(String key) {
        int hash = key == null ? 0 : key.hashCode();
        return locks[Math.floorMod(hash, locks.length)];
    }
}
