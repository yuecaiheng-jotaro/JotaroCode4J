package com.kama.piagent.agent.runtime;

/**
 * 一轮结束后的调度决定。END 在 turn_end 之后直接结束本次运行；
 * CONTINUE 保证再发起一次模型请求。
 */
public enum TurnDecision {
    END,
    CONTINUE
}
