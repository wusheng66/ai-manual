package com.learning.aiagenttest.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class ReActAgent extends BaseAgent {


    private static final Logger log = LoggerFactory.getLogger(ReActAgent.class);

    /**
     * process current state and determine next step
     * @return should act
     */
    public abstract boolean think();

    /**
     * execute determined action
     * @return
     */
    public abstract String act();

    @Override
    public String step() {
        try {
            boolean shouldAct = this.think();
            if (!shouldAct) {
                return "Thinking finished - No action.";
            }
            return this.act();
        } catch (Exception e) {
            log.error("Execute error", e);
            return "Step executed error: " + e.getMessage();
        }
    }
}
