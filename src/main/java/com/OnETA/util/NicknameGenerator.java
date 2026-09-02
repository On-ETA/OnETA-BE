package com.OnETA.util;

import java.util.Random;

public class NicknameGenerator {
    
    private static final String[] ADJECTIVES = {
        "노란", "파란", "빨간", "하얀", "까만", 
        "용감한", "행복한", "재빠른", "똑똑한", "귀여운"
    };
    
    private static final String[] NOUNS = {
        "코끼리", "사자", "호랑이", "토끼", "독수리", 
        "고양이", "강아지", "거북이", "돌고래", "다람쥐"
    };
    
    private static final Random RANDOM = new Random();

    public static String generate() {
        String adjective = ADJECTIVES[RANDOM.nextInt(ADJECTIVES.length)];
        String noun = NOUNS[RANDOM.nextInt(NOUNS.length)];
        
        // 뒤에 4자리 랜덤 숫자를 붙여서 중복 가능성을 낮춥니다.
        int randomNumber = 1000 + RANDOM.nextInt(9000); 
        
        return adjective + noun + randomNumber;
    }
}
