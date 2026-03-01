package com.creysvpn.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final int REQUEST_OVERLAY = 2;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Проверка разрешений при запуске
        checkAllPermissions();

        // Кнопка "НАЧАТЬ"
        Button btnStart = findViewById(R.id.btnStart);
        btnStart.setOnClickListener(v -> {
            // Открываем VPN Activity
            Intent intent = new Intent(MainActivity.this, VpnActivity.class);
            startActivity(intent);
        });
    }

    // Проверка всех разрешений
    private void checkAllPermissions() {
        // Разрешение "Поверх других приложений"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this,
                        "Требуется разрешение на отображение поверх приложений",
                        Toast.LENGTH_LONG).show();

                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
                startActivityForResult(intent, REQUEST_OVERLAY);
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_OVERLAY) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, "Разрешение получено", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this,
                            "Разрешение не получено. Некоторые функции могут не работать",
                            Toast.LENGTH_LONG).show();
                }
            }
        }
    }
}
