package br.araujo.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.text.TextUtils;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Launcher minimalista: lista de apps em texto, busca e apps ocultos.
 * Sem bibliotecas, sem ícones carregados, sem serviços em segundo plano.
 */
public final class MainActivity extends Activity {

    private static final String PREFS = "leve";
    private static final String KEY_HIDDEN = "hidden";
    /** Atualizações da Play Store disparam vários eventos seguidos: junta tudo numa recarga só. */
    private static final long RELOAD_DELAY_MS = 400;

    private static final Pattern ACCENTS = Pattern.compile("\\p{M}+");
    /** Comic Neue (licença OFL), carregada uma vez por processo. */
    private static Typeface font;

    /** Cache em memória: reabrir a tela inicial não recarrega nada. */
    private static List<App> cache;
    /** Descarta resultados de recargas antigas que terminem depois de uma mais nova. */
    private static int generation;

    private LauncherApps launcherApps;
    private SharedPreferences prefs;
    private final Set<String> hidden = new HashSet<>();
    private final List<App> visible = new ArrayList<>();
    private final List<App> shown = new ArrayList<>();
    private final List<App> contains = new ArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable reloadTask = this::reloadNow;

    /**
     * Lista em ScrollView simples, sem GridView: o GridView guarda estado interno
     * de seleção/foco que às vezes ficava preso ao voltar pelo botão home e
     * matava a rolagem (o toque continuava funcionando).
     */
    private ScrollView scroll;
    private LinearLayout rows;
    /** Células já criadas, duas por linha; são reaproveitadas a cada filtro. */
    private final List<TextView> cells = new ArrayList<>();
    private EditText search;
    private TextView hiddenFooter;
    private String query = "";
    private int hiddenCount;

    private final LauncherApps.Callback callback = new LauncherApps.Callback() {
        @Override public void onPackageRemoved(String p, UserHandle u) { reload(); }
        @Override public void onPackageAdded(String p, UserHandle u) { reload(); }
        @Override public void onPackageChanged(String p, UserHandle u) { reload(); }
        @Override public void onPackagesAvailable(String[] p, UserHandle u, boolean r) { reload(); }
        @Override public void onPackagesUnavailable(String[] p, UserHandle u, boolean r) { reload(); }
    };

    static final class App {
        final String label, display, norm, key, pkg;
        final ComponentName component;
        final UserHandle user;

        App(String label, ComponentName c, UserHandle user, long serial, boolean work) {
            this.label = label;
            this.display = work ? label + "  ·  trabalho" : label;
            this.norm = normalize(label);
            this.component = c;
            this.pkg = c.getPackageName();
            this.user = user;
            this.key = c.flattenToShortString() + "#" + serial;
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        launcherApps = (LauncherApps) getSystemService(Context.LAUNCHER_APPS_SERVICE);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        hidden.addAll(prefs.getStringSet(KEY_HIDDEN, Collections.emptySet()));

        buildUi();
        launcherApps.registerCallback(callback, main);

        if (cache != null) {
            apply(cache);
            reload();      // confere em segundo plano, sem pressa
        } else {
            reloadNow();   // primeira abertura: carrega já
        }
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        launcherApps.unregisterCallback(callback);
        super.onDestroy();
    }

    /** Botão Home pressionado com o launcher já aberto: volta ao topo. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        resetView();
    }

    @Override
    public void onBackPressed() {
        resetView();
    }

    @Override
    protected void onStop() {
        super.onStop();
        resetView();
    }

    private void resetView() {
        if (search.length() > 0) search.setText("");
        hideKeyboard();
        scrollToTop();
    }

    private void scrollToTop() {
        scroll.scrollTo(0, 0);
        scroll.fling(0); // cancela um deslize que ainda estivesse em andamento
    }

    // ---------------------------------------------------------------- UI

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(12));

        if (font == null) {
            try { font = Typeface.createFromAsset(getAssets(), "comic.ttf"); }
            catch (RuntimeException e) { font = Typeface.create("casual", Typeface.NORMAL); }
        }

        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);

        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setFocusable(false);
        scroll.addView(rows, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // Arrastar a lista com o teclado aberto fecha o teclado.
        scroll.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_MOVE && search.hasFocus()) hideKeyboard();
            return false;
        });

        hiddenFooter = row();
        hiddenFooter.setTextColor(0xFF666666);
        hiddenFooter.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        hiddenFooter.setVisibility(View.GONE);
        hiddenFooter.setOnClickListener(v -> showHiddenDialog());

        search = new EditText(this);
        search.setHint("buscar");
        search.setHintTextColor(0xFF555555);
        search.setTextColor(Color.WHITE);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        search.setTypeface(font);
        search.setSingleLine(true);
        search.setBackground(null);
        search.setPadding(0, dp(12), 0, dp(12));
        search.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        search.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD); // teclado sem sugestões/autocorreção
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                String q = normalize(s.toString().trim());
                if (q.equals(query)) return;
                query = q;
                filter();
                scrollToTop();
            }
        });
        search.setOnEditorActionListener((v, action, ev) -> {
            boolean enter = action == EditorInfo.IME_ACTION_GO
                    || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER && ev.getAction() == KeyEvent.ACTION_DOWN);
            if (enter && !shown.isEmpty()) launch(shown.get(0));
            return enter;
        });

        View line = new View(this);
        line.setBackgroundColor(0xFF222222);

        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(hiddenFooter, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        root.addView(search, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);
    }

    private TextView row() {
        TextView t = new TextView(this);
        t.setTextColor(0xFFEEEEEE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        t.setTypeface(font);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setIncludeFontPadding(false);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setMinHeight(dp(48)); // alvo de toque confortável
        t.setPadding(0, dp(10), 0, dp(10));
        return t;
    }

    /** Cria mais uma linha da grade, com duas células de mesma largura. */
    private void addRow() {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        for (int col = 0; col < 2; col++) {
            TextView t = row();
            t.setFocusable(false);
            t.setOnClickListener(v -> {
                Object a = v.getTag();
                if (a instanceof App) launch((App) a);
            });
            t.setOnLongClickListener(v -> {
                Object a = v.getTag();
                if (a instanceof App) showAppMenu((App) a);
                return true;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (col == 0) lp.rightMargin = dp(16);
            line.addView(t, lp);
            cells.add(t);
        }
        rows.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** Mostra {@link #shown} na grade, reaproveitando as células existentes. */
    private void render() {
        int n = shown.size();
        int needed = (n + 1) / 2;
        while (rows.getChildCount() < needed) addRow();
        for (int i = 0; i < cells.size(); i++) {
            TextView t = cells.get(i);
            if (i < n) {
                App a = shown.get(i);
                Object old = t.getTag();
                if (!(old instanceof App) || !((App) old).display.equals(a.display)) t.setText(a.display);
                t.setTag(a);
                t.setVisibility(View.VISIBLE);
            } else {
                t.setTag(null);
                t.setVisibility(View.INVISIBLE);
            }
        }
        for (int r = 0; r < rows.getChildCount(); r++) {
            rows.getChildAt(r).setVisibility(r < needed ? View.VISIBLE : View.GONE);
        }
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
        if (search.hasFocus()) search.clearFocus();
    }

    // ---------------------------------------------------------------- dados

    static String normalize(String s) {
        return ACCENTS.matcher(Normalizer.normalize(s, Normalizer.Form.NFD))
                .replaceAll("")
                .toLowerCase(Locale.ROOT);
    }

    /** Agenda uma recarga; eventos em sequência viram uma só. */
    private void reload() {
        main.removeCallbacks(reloadTask);
        main.postDelayed(reloadTask, RELOAD_DELAY_MS);
    }

    /** Carrega a lista de apps fora da thread principal. */
    private void reloadNow() {
        main.removeCallbacks(reloadTask);
        final int gen = ++generation;
        // Só o contexto da aplicação vai para a thread: a Activity não vaza.
        final Context ctx = getApplicationContext();
        Thread t = new Thread(() -> {
            UserManager um = (UserManager) ctx.getSystemService(Context.USER_SERVICE);
            LauncherApps la = (LauncherApps) ctx.getSystemService(Context.LAUNCHER_APPS_SERVICE);
            String self = ctx.getPackageName();
            UserHandle me = Process.myUserHandle();
            List<App> apps = new ArrayList<>();
            for (UserHandle user : um.getUserProfiles()) {
                long serial = um.getSerialNumberForUser(user);
                boolean work = !user.equals(me);
                for (LauncherActivityInfo info : la.getActivityList(null, user)) {
                    ComponentName c = info.getComponentName();
                    if (c.getPackageName().equals(self)) continue;
                    apps.add(new App(info.getLabel().toString(), c, user, serial, work));
                }
            }
            Collator col = Collator.getInstance(new Locale("pt", "BR"));
            col.setStrength(Collator.PRIMARY);
            Collections.sort(apps, (a, b) -> col.compare(a.label, b.label));
            main.post(() -> {
                if (gen != generation || isDestroyed()) return;
                apply(apps);
            });
        }, "carregar-apps");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private void apply(List<App> apps) {
        cache = apps;
        visible.clear();
        hiddenCount = 0;
        for (App a : apps) {
            if (hidden.contains(a.key)) hiddenCount++;
            else visible.add(a);
        }
        filter();
    }

    private void filter() {
        shown.clear();
        if (query.isEmpty()) {
            shown.addAll(visible);
        } else {
            // Primeiro os que começam com o termo, depois os que contêm.
            contains.clear();
            for (App a : visible) {
                if (a.norm.startsWith(query)) shown.add(a);
                else if (a.norm.contains(query)) contains.add(a);
            }
            shown.addAll(contains);
            contains.clear();
        }
        updateFooter(hiddenCount > 0 && query.isEmpty());
        render();
    }

    /** Linha "apps ocultos" fica abaixo da grade, só quando há apps ocultos. */
    private void updateFooter(boolean show) {
        if (show) hiddenFooter.setText("apps ocultos (" + hiddenCount + ")");
        hiddenFooter.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void saveHidden() {
        prefs.edit().putStringSet(KEY_HIDDEN, new HashSet<>(hidden)).apply();
        if (cache != null) apply(cache);
    }

    // ---------------------------------------------------------------- ações

    private void launch(App a) {
        try {
            launcherApps.startMainActivity(a.component, a.user, null, null);
        } catch (Exception e) {
            Toast.makeText(this, "Não foi possível abrir " + a.label, Toast.LENGTH_SHORT).show();
            reloadNow();
        }
    }

    private void showAppMenu(App a) {
        hideKeyboard();
        String[] opts = {"Ocultar da lista", "Informações do app", "Desinstalar"};
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(a.label)
                .setItems(opts, (d, which) -> {
                    if (which == 0) {
                        hidden.add(a.key);
                        saveHidden();
                        Toast.makeText(this, a.label + " oculto", Toast.LENGTH_SHORT).show();
                    } else if (which == 1) {
                        launcherApps.startAppDetailsActivity(a.component, a.user, null, null);
                    } else {
                        Intent i = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + a.pkg));
                        i.putExtra(Intent.EXTRA_USER, a.user);
                        startActivity(i);
                    }
                })
                .show();
    }

    private void showHiddenDialog() {
        if (cache == null) return;
        final List<App> list = new ArrayList<>();
        for (App a : cache) if (hidden.contains(a.key)) list.add(a);
        if (list.isEmpty()) return;
        String[] names = new String[list.size()];
        for (int i = 0; i < names.length; i++) names[i] = list.get(i).display;
        // Tocar num nome abre o app sem desocultar; "Reexibir…" devolve à lista.
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Apps ocultos")
                .setItems(names, (d, w) -> launch(list.get(w)))
                .setPositiveButton("Reexibir…", (d, w) -> unhideDialog(list, names))
                .setNegativeButton("Fechar", null)
                .show();
    }

    private void unhideDialog(List<App> list, String[] names) {
        boolean[] checked = new boolean[names.length];
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Marque para voltar a exibir")
                .setMultiChoiceItems(names, checked, (d, w, c) -> checked[w] = c)
                .setPositiveButton("Exibir", (d, w) -> {
                    for (int i = 0; i < checked.length; i++) if (checked[i]) hidden.remove(list.get(i).key);
                    saveHidden();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }
}
