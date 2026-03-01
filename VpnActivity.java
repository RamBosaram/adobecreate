package com.creysvpn.app;

import android.app.Activity;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

public class VpnActivity extends Activity {

    private static final int REQUEST_VPN = 1;
    private static final int REQUEST_OVERLAY = 2;

    private Button btnVpn;
    private TextView tvStatus;
    private boolean vpnRunning = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_vpn);

        btnVpn = findViewById(R.id.btnVpn);
        tvStatus = findViewById(R.id.tvStatus);

        // Проверка разрешения "Поверх других приложений"
        checkOverlayPermission();

        btnVpn.setOnClickListener(v -> {
            if (!vpnRunning) {
                startVPN();
            } else {
                stopVPN();
            }
        });
    }

    private void checkOverlayPermission() {
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

    private void startVPN() {
        // Проверка разрешения overlay
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Сначала дай разрешение на отображение поверх приложений",
                        Toast.LENGTH_LONG).show();
                checkOverlayPermission();
                return;
            }
        }

        // Запрос VPN разрешения
        Intent intent = VpnService.prepare(this);
        if (intent != null) {
            startActivityForResult(intent, REQUEST_VPN);
        } else {
            onActivityResult(REQUEST_VPN, RESULT_OK, null);
        }
    }

    private void stopVPN() {
        // Останавливаем VPN
        Intent vpnIntent = new Intent(this, PcapVpnService.class);
        stopService(vpnIntent);

        // Останавливаем плавающие окна
        Intent floatingIntent = new Intent(this, OverlayService.class);
        stopService(floatingIntent);

        vpnRunning = false;
        btnVpn.setText("▶");
        btnVpn.setBackgroundResource(R.drawable.button_vpn_off);
        tvStatus.setText("ОТКЛЮЧЕНО");

        Toast.makeText(this, "VPN остановлен", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_VPN) {
            if (resultCode == RESULT_OK) {
                // Запускаем VPN Service
∆˜                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(vpnIntent);
                } else {
                    startService(vpnIntent);
                }

                // Запускаем плавающие окна
                Intent floatingIntent = new Intent(this, OverlayService.class);
                startService(floatingIntent);

                vpnRunning = true;
                btnVpn.setText("■");
                btnVpn.setBackgroundResource(R.drawable.button_vpn_on);
                tvStatus.setText("ПОДКЛЮЧЕНО");

                Toast.makeText(this, "VPN запущен. Плавающие окна активны!",
                        Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Разрешение VPN отклонено",
                        Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == REQUEST_OVERLAY) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, "Разрешение получено! Теперь запусти VPN",
                            Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this,
                            "Разрешение не получено. Плавающие окна не будут работать!",
                            Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (vpnRunning) {
            stopVPN();
        }
    }
}
