package com.learning.aiagenttest.agent;

import com.learning.aiagenttest.model.AgentState;

public class BaseAgent {


    private String name;

    private String systemPrompt;

    private String nextStepPrompt;

    private AgentState state = AgentState.IDLE;

    private int currentStep = 0;

    private int maxStep = 100;


}
