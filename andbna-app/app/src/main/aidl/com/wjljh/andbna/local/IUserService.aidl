package com.wjljh.andbna.local;

/**
 * 跑在 shell 进程里的执行接口。
 *
 * 为什么需要它：Shizuku 13.x 的 `Shizuku.newProcess` 不是公开 API，
 * 想在 shell 身份下执行命令，官方做法就是声明一个 UserService —— 
 * Shizuku 会在 uid 2000 的进程里实例化它，通过这个 AIDL 调回来。
 */
interface IUserService {
    /** 执行一条 shell 命令，返回 stdout+stderr（合并） */
    String exec(String cmd);
}
