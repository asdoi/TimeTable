package com.ulan.timetable.activities;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.browser.customtabs.CustomTabsIntent;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.preference.PreferenceManager;
import androidx.viewpager.widget.ViewPager;

import com.afollestad.materialdialogs.MaterialDialog;
import com.ajts.androidmads.library.ExcelToSQLite;
import com.ajts.androidmads.library.SQLiteToExcel;
import com.google.android.material.navigation.NavigationView;
import com.google.android.material.tabs.TabLayout;
import com.mikepenz.aboutlibraries.LibsBuilder;
import com.pd.chocobar.ChocoBar;
import com.ulan.timetable.R;
import com.ulan.timetable.adapters.FragmentsTabAdapter;
import com.ulan.timetable.fragments.WeekdayFragment;
import com.ulan.timetable.profiles.ProfileManagement;
import com.ulan.timetable.receivers.DoNotDisturbReceiversKt;
import com.ulan.timetable.utils.AlertDialogsHelper;
import com.ulan.timetable.utils.DbHelper;
import com.ulan.timetable.utils.NotificationUtil;
import com.ulan.timetable.utils.PreferenceUtil;
import com.ulan.timetable.utils.ShortcutUtils;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import info.isuru.sheriff.enums.SheriffPermission;
import info.isuru.sheriff.helper.Sheriff;
import info.isuru.sheriff.interfaces.PermissionListener;
import saschpe.android.customtabs.CustomTabsHelper;
import saschpe.android.customtabs.WebViewFallback;


public class MainActivity extends AppCompatActivity implements NavigationView.OnNavigationItemSelectedListener {

    private FragmentsTabAdapter adapter;
    private ViewPager viewPager;
    private boolean switchSevenDays;

    private static final int showNextDayAfterSpecificHour = 20;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(PreferenceUtil.getGeneralThemeNoActionBar(this));
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ProfileManagement.initProfiles(this);

        if (Build.VERSION.SDK_INT >= 25) {
            ShortcutUtils.Companion.createShortcuts(this);
        }
        if (!PreferenceUtil.hasStartActivityBeenShown(this)) {
            new MaterialDialog.Builder(this)
                    .content(R.string.first_start_setup)
                    .positiveText(R.string.ok)
                    .onPositive((v, w) -> startActivity(new Intent(this, TimeSettingsActivity.class)))
                    .show();
        }

        initAll();
    }

    @Override
    public void onStart() {
        super.onStart();
        DoNotDisturbReceiversKt.setDoNotDisturbReceivers(this, false);
        setupWeeksTV();
    }

    private void initAll() {
        NotificationUtil.sendNotificationCurrentLesson(this, false);
        PreferenceUtil.setDoNotDisturb(this, PreferenceUtil.doNotDisturbDontAskAgain(this));
        initSpinner();

        setupWeeksTV();

        NavigationView navigationView = findViewById(R.id.nav_view);
        navigationView.setNavigationItemSelectedListener(this);
        View headerview = navigationView.getHeaderView(0);
        headerview.findViewById(R.id.nav_header_main_settings).setOnClickListener((View v) -> startActivity(new Intent(this, SettingsActivity.class)));
        TextView title = headerview.findViewById(R.id.nav_header_main_title);
        title.setText(R.string.app_name);

        TextView desc = headerview.findViewById(R.id.nav_header_main_desc);
        desc.setText(R.string.nav_drawer_description);

        PreferenceManager.setDefaultValues(this, R.xml.settings, false);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        DrawerLayout drawer = findViewById(R.id.drawer_layout);

        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(
                this, drawer, toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close);
        drawer.addDrawerListener(toggle);
        toggle.syncState();

        setupSevenDaysPref();
        setupFragments();
        setupCustomDialog();

        if (switchSevenDays) changeFragments(true);
    }

    private boolean dontfire = true;

    private void initSpinner() {
        //Set Profiles
        Spinner parentSpinner = findViewById(R.id.profile_spinner);

        if (ProfileManagement.isMoreThanOneProfile()) {
            parentSpinner.setVisibility(View.VISIBLE);
            parentSpinner.setEnabled(true);
            List<String> list = ProfileManagement.getProfileListNames();
            list.add(getString(R.string.profiles_edit));
            ArrayAdapter<String> dataAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, list);
            dataAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            parentSpinner.setAdapter(dataAdapter);
            dontfire = true;
            parentSpinner.setSelection(ProfileManagement.getSelectedProfilePosition(this));
            parentSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(@NonNull AdapterView<?> parent, View view, int position, long id) {
                    if (dontfire) {
                        dontfire = false;
                        return;
                    }

                    String item = parent.getItemAtPosition(position).toString();
                    if (item.equals(getString(R.string.profiles_edit))) {
                        Intent intent = new Intent(getBaseContext(), ProfileActivity.class);
                        startActivity(intent);
                        finish();
                    } else {
                        //Change profile position
                        ProfileManagement.setSelectedProfile(getApplicationContext(), position);
                        startActivity(new Intent(getBaseContext(), MainActivity.class));
                        finish();
                    }
                }

                @Override
                public void onNothingSelected(AdapterView<?> adapterView) {

                }
            });
        } else {
            parentSpinner.setVisibility(View.GONE);
            parentSpinner.setEnabled(false);
        }
    }

    private void setupWeeksTV() {
        TextView weekView = findViewById(R.id.main_week_tV);
        if (PreferenceUtil.isTwoWeeksEnabled(this)) {
            weekView.setVisibility(View.VISIBLE);
            if (PreferenceUtil.isEvenWeek(this, Calendar.getInstance()))
                weekView.setText(R.string.even_week);
            else
                weekView.setText(R.string.odd_week);
        } else
            weekView.setVisibility(View.GONE);
    }

    private void setupFragments() {
        adapter = new FragmentsTabAdapter(getSupportFragmentManager());
        viewPager = findViewById(R.id.viewPager);
        TabLayout tabLayout = findViewById(R.id.tabLayout);

        WeekdayFragment mondayFragment = new WeekdayFragment(WeekdayFragment.KEY_MONDAY_FRAGMENT);
        WeekdayFragment tuesdayFragment = new WeekdayFragment(WeekdayFragment.KEY_TUESDAY_FRAGMENT);
        WeekdayFragment wednesdayFragment = new WeekdayFragment(WeekdayFragment.KEY_WEDNESDAY_FRAGMENT);
        WeekdayFragment thursdayFragment = new WeekdayFragment(WeekdayFragment.KEY_THURSDAY_FRAGMENT);
        WeekdayFragment fridayFragment = new WeekdayFragment(WeekdayFragment.KEY_FRIDAY_FRAGMENT);

        adapter.addFragment(mondayFragment, getResources().getString(R.string.monday));
        adapter.addFragment(tuesdayFragment, getResources().getString(R.string.tuesday));
        adapter.addFragment(wednesdayFragment, getResources().getString(R.string.wednesday));
        adapter.addFragment(thursdayFragment, getResources().getString(R.string.thursday));
        adapter.addFragment(fridayFragment, getResources().getString(R.string.friday));

        viewPager.setAdapter(adapter);

        int day = getFragmentChoosingDay();
        viewPager.setCurrentItem(day == 1 ? 6 : day - 2, true);

        tabLayout.setupWithViewPager(viewPager);
    }

    private void changeFragments(boolean isChecked) {
        if (isChecked) {
            TabLayout tabLayout = findViewById(R.id.tabLayout);
            int day = getFragmentChoosingDay();
            adapter.addFragment(new WeekdayFragment(WeekdayFragment.KEY_SATURDAY_FRAGMENT), getResources().getString(R.string.saturday));
            adapter.addFragment(new WeekdayFragment(WeekdayFragment.KEY_SUNDAY_FRAGMENT), getResources().getString(R.string.sunday));
            viewPager.setAdapter(adapter);
            viewPager.setCurrentItem(day == 1 ? 6 : day - 2, true);
            tabLayout.setupWithViewPager(viewPager);
        } else {
            if (adapter.getFragmentList().size() > 5) {
                adapter.removeFragment(new WeekdayFragment(WeekdayFragment.KEY_SATURDAY_FRAGMENT), 5);
                adapter.removeFragment(new WeekdayFragment(WeekdayFragment.KEY_SUNDAY_FRAGMENT), 5);
            }
        }
        adapter.notifyDataSetChanged();
    }

    private int getFragmentChoosingDay() {
        Calendar calendar = Calendar.getInstance();
        int day = calendar.get(Calendar.DAY_OF_WEEK);
        int hour = calendar.get(Calendar.HOUR_OF_DAY);
        //If its after 18 o'clock, show the next day
        if (hour >= showNextDayAfterSpecificHour) {
            day++;
        }
        if (day > 7) { //Calender.Saturday
            day = day - 7; //1 = Calendar.Sunday, 2 = Calendar.Monday etc.
        }
        //If Saturday/Sunday are hidden, switch to Monday
        if (!switchSevenDays && (day == Calendar.SUNDAY || day == Calendar.SATURDAY)) {
            day = Calendar.MONDAY;
        }
        return day;
    }

    private void setupCustomDialog() {
        final View alertLayout = getLayoutInflater().inflate(R.layout.dialog_add_subject, null);
        AlertDialogsHelper.getAddSubjectDialog(MainActivity.this, alertLayout, adapter, viewPager);
    }

    private void setupSevenDaysPref() {
        switchSevenDays = PreferenceUtil.isSevenDays(this);
    }

    @Override
    public void onBackPressed() {
        DrawerLayout drawer = findViewById(R.id.drawer_layout);
        if (drawer.isDrawerOpen(GravityCompat.START)) {
            drawer.closeDrawer(GravityCompat.START);
        }
        ProfileManagement.resetSelectedProfile(this);
        finishAffinity();
    }

    @Override
    public boolean onCreateOptionsMenu(@NonNull Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_settings) {
            Intent settings = new Intent(MainActivity.this, SettingsActivity.class);
            startActivity(settings);
        } else if (item.getItemId() == R.id.action_backup) {
            backup();
        } else if (item.getItemId() == R.id.action_restore) {
            restore();
        } else if (item.getItemId() == R.id.action_remove_all) {
            deleteAll();
        } else if (item.getItemId() == R.id.action_about_libs) {
            new LibsBuilder()
                    .withActivityTitle(getString(R.string.about_libs_title))
                    .withAboutIconShown(true)
                    .withFields(R.string.class.getFields())
                    .withLicenseShown(true)
                    .withAboutDescription(getString(R.string.nav_drawer_description))
                    .withAboutAppName(getString(R.string.app_name))
                    .start(this);
        } else if (item.getItemId() == R.id.action_profiles) {
            Intent intent = new Intent(getBaseContext(), ProfileActivity.class);
            startActivity(intent);
            finish();
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.exams) {
            Intent exams = new Intent(MainActivity.this, ExamsActivity.class);
            startActivity(exams);
        } else if (itemId == R.id.homework) {
            Intent homework = new Intent(MainActivity.this, HomeworkActivity.class);
            startActivity(homework);
        } else if (itemId == R.id.notes) {
            Intent note = new Intent(MainActivity.this, NotesActivity.class);
            startActivity(note);
        } else if (itemId == R.id.settings) {
            Intent settings = new Intent(MainActivity.this, SettingsActivity.class);
            startActivity(settings);
        } else if (itemId == R.id.schoolwebsitemenu) {
            String schoolWebsite = PreferenceManager.getDefaultSharedPreferences(this).getString(SettingsActivity.KEY_SCHOOL_WEBSITE_SETTING, null);
            if (!TextUtils.isEmpty(schoolWebsite)) {
                openUrlInChromeCustomTab(schoolWebsite);
            } else {
                ChocoBar.builder().setActivity(this)
                        .setText(getString(R.string.please_set_school_website_url))
                        .setDuration(ChocoBar.LENGTH_LONG)
                        .red()
                        .show();
            }
        } else if (itemId == R.id.teachers) {
            Intent teacher = new Intent(MainActivity.this, TeachersActivity.class);
            startActivity(teacher);
        } else if (itemId == R.id.summary) {
            Intent teacher = new Intent(MainActivity.this, SummaryActivity.class);
            startActivity(teacher);
        }
        DrawerLayout drawer = findViewById(R.id.drawer_layout);
        drawer.closeDrawer(GravityCompat.START);
        return true;
    }

    //Backup
    private static final int CREATE_BACKUP_REQUEST_CODE = 40;
    private static final int OPEN_BACKUP_REQUEST_CODE = 41;
    private static final String BACKUP_FILENAME = "Timetable_Backup.xls";

    public void onActivityResult(int requestCode, int resultCode, Intent resultData) {
        super.onActivityResult(requestCode, resultCode, resultData);

        if (resultCode == Activity.RESULT_OK) {
            if (requestCode == CREATE_BACKUP_REQUEST_CODE) {
                if (resultData != null) {
                    //Get File path
                    backup(getFilePath(resultData));
                }
            } else if (requestCode == OPEN_BACKUP_REQUEST_CODE) {
                if (resultData != null) {
                    //Get File path
                    restore(getFilePath(resultData));
                }
            }

        }
    }

    private String getFilePath(Intent intent) {
        Uri uri = intent.getData();
        File file = new File(uri.getPath());//create path from uri
        final String[] split = file.getPath().split(":");//split the path.
        String filePath = split[1];//assign it to a string(your choice).
        return "/storage/emulated/0/" + filePath;
    }

    public void backup() {
        SimpleDateFormat timeStampFormat = new SimpleDateFormat("yyyyMMdd", Locale.getDefault());
        String filename = timeStampFormat.format(new Date());

        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/vnd.ms-excel");
        intent.putExtra(Intent.EXTRA_TITLE, "Timetable_Backup_" + filename + ".xls");

        startActivityForResult(intent, CREATE_BACKUP_REQUEST_CODE);
    }

    @SuppressWarnings("deprecation")
    public void backup(String path) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {

            requestPermission(() -> backup(path), SheriffPermission.STORAGE);
            return;
        }

//        String path = Environment.getExternalStoragePublicDirectory(Build.VERSION.SDK_INT >= 19 ? Environment.DIRECTORY_DOCUMENTS : Environment.DIRECTORY_DOWNLOADS).toString();
//        File folder = new File(path);
//        if (!folder.exists()) {
//            folder.mkdirs();
//        }

        AppCompatActivity activity = this;

        SQLiteToExcel sqliteToExcel = new SQLiteToExcel(this, DbHelper.getDBName(this), path);
        sqliteToExcel.exportAllTables(path, new SQLiteToExcel.ExportListener() {
            @Override
            public void onStart() {

            }

            @Override
            public void onCompleted(String filePath) {
                runOnUiThread(() -> ChocoBar.builder().setActivity(activity)
                        .setText(getString(R.string.backup_successful, Build.VERSION.SDK_INT >= 19 ? getString(R.string.Documents) : getString(R.string.Downloads)))
                        .setDuration(ChocoBar.LENGTH_LONG)
                        .green()
                        .show());
            }

            @Override
            public void onError(Exception e) {
                runOnUiThread(() -> ChocoBar.builder().setActivity(activity)
                        .setText(getString(R.string.backup_failed) + ": " + e.toString())
                        .setDuration(ChocoBar.LENGTH_LONG)
                        .red()
                        .show());
            }
        });
    }

    public void restore() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/vnd.ms-excel");

        startActivityForResult(intent, OPEN_BACKUP_REQUEST_CODE);
    }

    @SuppressWarnings("deprecation")
    public void restore(String path) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {

            requestPermission(this::restore, SheriffPermission.STORAGE);
            return;
        }

//        String path = Environment.getExternalStoragePublicDirectory(Build.VERSION.SDK_INT >= 19 ? Environment.DIRECTORY_DOCUMENTS : Environment.DIRECTORY_DOWNLOADS).toString() + File.separator + BACKUP_FILENAME;
//        File file = new File(path);
//        if (!file.exists()) {
//            ChocoBar.builder().setActivity(this)
//                    .setText(getString(R.string.no_backup_found_in_downloads, Build.VERSION.SDK_INT >= 19 ? getString(R.string.Documents) : getString(R.string.Downloads)))
//                    .setDuration(ChocoBar.LENGTH_LONG)
//                    .red()
//                    .show();
//            return;
//        }

        AppCompatActivity activity = this;
        DbHelper dbHelper = new DbHelper(this);
        dbHelper.deleteAll();

        ExcelToSQLite excelToSQLite = new ExcelToSQLite(getApplicationContext(), DbHelper.getDBName(this), false);
        excelToSQLite.importFromFile(path, new ExcelToSQLite.ImportListener() {
            @Override
            public void onStart() {

            }

            @Override
            public void onCompleted(String filePath) {
                runOnUiThread(() -> ChocoBar.builder().setActivity(activity)
                        .setText(getString(R.string.import_successful))
                        .setDuration(ChocoBar.LENGTH_LONG)
                        .green()
                        .show());
                initAll();
            }

            @Override
            public void onError(Exception e) {
                runOnUiThread(() -> ChocoBar.builder().setActivity(activity)
                        .setText(getString(R.string.import_failed) + ": " + e.toString())
                        .setDuration(ChocoBar.LENGTH_LONG)
                        .red()
                        .show());
            }
        });
    }

    public void deleteAll() {
        new MaterialDialog.Builder(this)
                .title(getString(R.string.delete_everything))
                .content(getString(R.string.delete_everything_desc))
                .positiveText(getString(R.string.yes))
                .onPositive((dialog, which) -> {
                    try {
                        DbHelper dbHelper = new DbHelper(this);
                        dbHelper.deleteAll();
                        ChocoBar.builder().setActivity(this)
                                .setText(getString(R.string.successfully_deleted_everything))
                                .setDuration(ChocoBar.LENGTH_LONG)
                                .green()
                                .show();
                        initAll();
                    } catch (Exception e) {
                        ChocoBar.builder().setActivity(this)
                                .setText(getString(R.string.an_error_occurred))
                                .setDuration(ChocoBar.LENGTH_LONG)
                                .red()
                                .show();
                    }
                })
                .onNegative((dialog, which) -> dialog.dismiss())
                .negativeText(getString(R.string.no))
                .onNeutral((dialog, which) -> {
                    backup();
                    dialog.dismiss();
                })
                .neutralText(R.string.backup)
                .show();
    }

    private void openUrlInChromeCustomTab(String url) {
        Context context = this;
        try {
            CustomTabsIntent customTabsIntent = new CustomTabsIntent.Builder()
                    .addDefaultShareMenuItem()
                    .setToolbarColor(PreferenceUtil.getPrimaryColor(this))
                    .setShowTitle(true)
                    .build();

            // This is optional but recommended
            CustomTabsHelper.Companion.addKeepAliveExtra(context, customTabsIntent.intent);

            // This is where the magic happens...
            CustomTabsHelper.Companion.openCustomTab(context, customTabsIntent,
                    Uri.parse(url),
                    new WebViewFallback());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    //Permissions
    private Sheriff sheriffPermission;
    private static final int REQUEST_MULTIPLE_PERMISSION = 101;

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        sheriffPermission.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    protected void requestPermission(Runnable runAfter, SheriffPermission... permissions) {
        PermissionListener pl = new MyPermissionListener(runAfter);

        sheriffPermission = Sheriff.Builder()
                .with(this)
                .requestCode(REQUEST_MULTIPLE_PERMISSION)
                .setPermissionResultCallback(pl)
                .askFor(permissions)
                .rationalMessage(getString(R.string.permission_request_message))
                .build();

        sheriffPermission.requestPermissions();
    }

    private class MyPermissionListener implements PermissionListener {
        final Runnable runAfter;

        MyPermissionListener(Runnable r) {
            runAfter = r;
        }

        @Override
        public void onPermissionsGranted(int requestCode, ArrayList<String> acceptedPermissionList) {
            if (runAfter == null)
                return;
            try {
                runAfter.run();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onPermissionsDenied(int requestCode, ArrayList<String> deniedPermissionList) {
            // setup the alert builder
            MaterialDialog.Builder builder = new MaterialDialog.Builder(MainActivity.this);
            builder.title(getString(R.string.permission_required));
            builder.content(getString(R.string.permission_required_description));

            // add the buttons
            builder.onPositive((dialog, which) -> {
                openAppPermissionSettings();
                dialog.dismiss();
            });
            builder.positiveText(getString(R.string.permission_ok_button));

            builder.negativeText(getString(R.string.permission_cancel_button));
            builder.onNegative((dialog, which) -> dialog.dismiss());

            // create and show the alert dialog
            MaterialDialog dialog = builder.build();
            dialog.show();
        }
    }

    private void openAppPermissionSettings() {
        Intent intent = new Intent();
        intent.setAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        Uri uri = Uri.fromParts("package", getApplicationContext().getPackageName(), null);
        intent.setData(uri);
        startActivity(intent);
    }
}
