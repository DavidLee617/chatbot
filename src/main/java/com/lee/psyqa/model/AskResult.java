package com.lee.psyqa.model;

import java.util.List;

public record AskResult(String answer, List<SourceRef> sources, Route route) {

    /** CLARIFY（T9）：新对话里的指代可能指向以前聊过的几个主题，先反问，不检索、不调模型。 */
    public enum Route { ANSWERED, REFUSED, CRISIS, CLARIFY }
}
