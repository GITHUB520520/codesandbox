package com.project.codesandbox.model;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
@Builder
public class CodeExecuteRequest implements Serializable {

    private String code;

    private String language;

    private List<String> input;

    public static final long serialVersionUID = 1L;
}
