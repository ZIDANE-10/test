package org.example.controller;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;


public class Test1 {

    public static void main(String[] args) {
        Semaphore semaphore = new Semaphore(5);
        new Thread(() -> {
            while (true){
                System.out.println(semaphore.availablePermits());
                LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(1));
            }
        }).start();
        for (int i = 0; i < 10; i++) {
            try {
                semaphore.acquire();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            getReq(semaphore,i);
        }
    }

    private static void getReq(Semaphore semaphore, int i) {
        new Thread(() -> {
            System.out.println(Thread.currentThread().getName() + " 获取到许可");
            try {
                Thread.sleep(1000*5);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            semaphore.release();
            System.out.println(Thread.currentThread().getName() + " 释放许可");
        },"thread-"+i).start();
    }


}
