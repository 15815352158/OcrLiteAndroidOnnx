package com.benjaminwan.ocr.invoice.app;

import android.app.Application;
import android.content.Context;

import com.benjaminwan.ocrlibrary.OcrEngine;

public class App extends Application {

    private static OcrEngine sEngine;

    public static synchronized OcrEngine getEngine(Context context) {
        if (sEngine == null) {
            sEngine = new OcrEngine(context.getApplicationContext());
        }
        return sEngine;
    }
}
