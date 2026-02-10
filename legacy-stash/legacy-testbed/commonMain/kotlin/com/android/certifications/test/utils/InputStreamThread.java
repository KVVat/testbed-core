package com.android.certifications.test.utils;
import java.io.*;
import java.util.*;

/**
 * InputStreamを読み込むスレッド
 */
public class InputStreamThread extends Thread {

    private BufferedReader br;

    private List<String> list = new ArrayList<String>();

    /** コンストラクター */
    public InputStreamThread(InputStream is) {
        br = new BufferedReader(new InputStreamReader(is));
    }

    /** コンストラクター */
    public InputStreamThread(InputStream is, String charset) {
        try {
            br = new BufferedReader(new InputStreamReader(is, charset));
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void run() {
        try {
            for (;;) {
                String line = br.readLine();
                if (line == null) 	break;
                list.add(line);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            try {
                br.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /** 文字列取得 */
    public List<String> getStringList() {
        return list;
    }
}