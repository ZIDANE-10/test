package org.example;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;

public class FileTest {
    public static void main(String[] args) throws Exception{
        String s = "asdf/sadf";
        int i = s.indexOf("/");
        System.out.println(s.substring(0,i));
        System.out.println(s.substring(i));
    }
}
