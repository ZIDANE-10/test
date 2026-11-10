package org.example.controller;

import com.alibaba.fastjson.JSON;
import org.example.domain.Test1;
import org.example.mapper.Test1Mapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/test")
public class TestController {

    @Autowired
    private Test1Mapper test1Mapper;

    @RequestMapping("/ab")
    @ResponseBody
    public Object ab() {
        Test1 one = test1Mapper.getOne();
        System.out.println(JSON.toJSONString(one));
        Object firstId = test1Mapper.getFirstId();
        System.out.println(firstId.toString());
        return one;
    }

    @RequestMapping("/resource")
    public Map<String,Object> resource() throws Exception{
        HashMap<String, Object> result = new HashMap<>();

        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources("classpath*:file/*file");
        File[] files = new File[resources.length];
        for (int i = 0; i < resources.length; i++) {
            files[i] = resources[i].getFile();
            result.put("file" + i, files[i].getName());
        }
        return result;
    }
}
