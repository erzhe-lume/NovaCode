package com.novacode.hook;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Duration;
import java.util.Map;

/**
 * YAML 中单条 hook 的映射类（第 12 章）。三要素格式：event + if（映射到 condition）+ action。
 */
public class HookConfig {

    private String id;
    private String event;
    private String condition;
    private boolean reject;
    private boolean once;
    private boolean async;
    private ActionConfig action;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getEvent() { return event; }
    public void setEvent(String event) { this.event = event; }
    @JsonProperty("if")
    public String getCondition() { return condition; }
    @JsonProperty("if")
    public void setCondition(String condition) { this.condition = condition; }
    public boolean isReject() { return reject; }
    public void setReject(boolean reject) { this.reject = reject; }
    public boolean isOnce() { return once; }
    public void setOnce(boolean once) { this.once = once; }
    public boolean isAsync() { return async; }
    public void setAsync(boolean async) { this.async = async; }
    public ActionConfig getAction() { return action; }
    public void setAction(ActionConfig action) { this.action = action; }

    /** 嵌套 action 对象：type + 按类型分用字段。 */
    public static class ActionConfig {
        private String type;
        private String command;
        private String message;
        private String url;
        private String method;
        private Map<String, String> headers;
        private String body;
        private int timeout; // 秒；<=0 用默认

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getCommand() { return command; }
        public void setCommand(String command) { this.command = command; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getMethod() { return method; }
        public void setMethod(String method) { this.method = method; }
        public Map<String, String> getHeaders() { return headers; }
        public void setHeaders(Map<String, String> headers) { this.headers = headers; }
        public String getBody() { return body; }
        public void setBody(String body) { this.body = body; }
        public int getTimeout() { return timeout; }
        public void setTimeout(int timeout) { this.timeout = timeout; }
    }

    /** 转内部 Hook record。action 缺失/type 非法由 HookEngine.validate 聚合报错。 */
    public Hook toHook() {
        HookActionType t = HookActionType.fromString(action != null ? action.getType() : null);
        Duration timeout = action != null && action.getTimeout() > 0
                ? Duration.ofSeconds(action.getTimeout()) : Duration.ZERO;
        HookAction a = new HookAction(t,
                action != null ? action.getCommand() : null,
                action != null ? action.getMessage() : null,
                action != null ? action.getUrl() : null,
                action != null ? action.getMethod() : null,
                action != null ? action.getHeaders() : null,
                action != null ? action.getBody() : null,
                timeout);
        return new Hook(id, HookEvent.fromString(event), condition, a, reject, once, async);
    }
}
