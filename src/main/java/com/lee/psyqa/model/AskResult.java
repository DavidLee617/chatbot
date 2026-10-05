package com.lee.psyqa.model;

import java.util.List;

public record AskResult(String answer, List<SourceRef> sources, Route route) {

    public enum Route { ANSWERED, REFUSED, CRISIS }
}
