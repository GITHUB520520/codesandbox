package com.project.codesandbox.enums;

import org.apache.commons.lang3.ObjectUtils;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public enum ExecuteStatusEnum {

    COMPILE_ERROR("编译错误", 0),
    RUNTIME_ERROR("运行错误", 1),
    SUCCESS("运行成功", 2),
    TIMEOUT("运行超时", 3),
    LANGUAGE_ERROR("系统暂不支持该语言", 4);


    private final String text;

    private final Integer value;

    ExecuteStatusEnum(String text, Integer value) {
        this.text = text;
        this.value = value;
    }

    /**
     * 获取值列表
     *
     * @return
     */
    public static List<Integer> getValues() {
        return Arrays.stream(values()).map(item -> item.value).collect(Collectors.toList());
    }

    /**
     * 根据 value 获取枚举
     *
     * @param value
     * @return
     */
    public static ExecuteStatusEnum getEnumByValue(Integer value) {
        if (ObjectUtils.isEmpty(value)) {
            return null;
        }
        for (ExecuteStatusEnum anEnum : ExecuteStatusEnum.values()) {
            if (anEnum.value.equals(value)) {
                return anEnum;
            }
        }
        return null;
    }

    public Integer getValue() {
        return value;
    }

    public String getText() {
        return text;
    }

}
