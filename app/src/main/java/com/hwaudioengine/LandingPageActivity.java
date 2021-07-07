package com.hwaudioengine;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import com.google.sample.oboe.manualtest.MainActivity;
import com.google.sample.oboe.manualtest.R;

public class LandingPageActivity extends Activity implements View.OnClickListener {

    private static final String TAG = LandingPageActivity.class.getSimpleName();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_landingpage);
        initView();
    }
    private void initView(){
        findViewById(R.id.ivHwAudioEngine).setOnClickListener(this);
        findViewById(R.id.ivOboeTester).setOnClickListener(this);
    }
    @Override
    public void onClick(View v) {
        Intent intent;

        switch (v.getId()) {
            case R.id.ivHwAudioEngine :
               intent = new Intent(this, HWMainActivity.class);
                 startActivity(intent);
                break;

            case R.id.ivOboeTester :
                intent = new Intent(this, MainActivity.class);
                startActivity(intent);
                break;

   /*         case R.id.ivFxLab :
                break;

    */
        }

    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
    }

    @Override
    public void onResume() {
        super.onResume();
    }

    @Override
    public void onPause() {
        super.onPause();

    }

    public void onDestroy() {
        super.onDestroy();
    }

}