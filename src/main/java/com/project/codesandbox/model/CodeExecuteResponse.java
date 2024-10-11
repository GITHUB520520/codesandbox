package com.project.codesandbox.model;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
@Builder
public class CodeExecuteResponse implements Serializable {

    private List<String> output;

    private boolean success;

    private String executeStatus;

    private String errorMessage;

    private Long memory;

    private Long time;

    public static final long serialVersionUID = 1L;
}
