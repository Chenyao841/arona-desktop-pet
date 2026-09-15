package com.cy.pojo;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class Result {
    private Integer code;
    private String operation;
    private String message;
    private String motion;
    private String soundEffect;
    private String specialEffect;
    private Integer sleepSeconds;
    private Object data;
    private List<Map<String, String>> segments;
    private List<Map<String, String>> sentences; // 句级表情流 [{text, motion}]，多句时前端逐句播放切表情

    public static Result success(){
        Result result = new Result();
        result.code = 1;
        return result;
    }
    public static Result success(Object object){
        Result result = new Result();
        result.data = object;
        result.code = 1;
        return result;
    }

    public static Result ok(String operation){
        Result result = new Result();
        result.code = 1;
        result.message = operation;
        return result;
    }

    public static Result error(String operation){
        Result result = new Result();
        result.code = 0;
        result.message = operation;
        return result;
    }

    public static Result reaction(String boatText, String motion){
        Result result = new Result();
        result.code = 1;
        result.message = boatText;
        result.motion = motion;
        return result;
    }

    public static Result reaction(String boatText, String motion, Object data){
        Result result = new Result();
        result.code = 1;
        result.message = boatText;
        result.motion = motion;
        result.data = data;
        return result;
    }

    public static Result reactionFail(String boatText, String motion) {
        Result result = new Result();
        result.code = 0;
        result.message = boatText;
        result.motion = motion;
        return result;
    }
}
