package com.jarvis.agent.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.jarvis.agent.agent.AgentSession
import com.jarvis.agent.agent.LogLine
import com.jarvis.agent.ai.Provider
import com.jarvis.agent.ai.ProviderConfig
import com.jarvis.agent.control.AgentOverlay
import com.jarvis.agent.control.OverlaySettings
import com.jarvis.agent.control.RootShell
import com.jarvis.agent.data.KeyStore
import com.jarvis.agent.util.Permissions
import com.jarvis.agent.voice.Voice
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var voice: Voice

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        voice = Voice(this)
        setContent {
            val dark = darkColorScheme(
                primary = Color(0xFF8B5CF6),
                secondary = Color(0xFFF59E0B),
                background = Color(0xFF0F0F14),
                surface = Color(0xFF1A1A22)
            )
            MaterialTheme(colorScheme = dark) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        App(voice)
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        AgentOverlay.instance?.setAppForeground(true)
    }

    override fun onStop() {
        super.onStop()
        AgentOverlay.instance?.setAppForeground(false)
        AgentOverlay.instance?.takeOverSpeech()
    }

    override fun onDestroy() {
        AgentSession.onFinalReply = null
        AgentOverlay.instance?.takeOverSpeech()
        voice.release()
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(voice: Voice) {
    var tab by remember { mutableIntStateOf(0) }
    val ctx = LocalContext.current
    // ابدأ بالإعدادات إن لم يكن هناك مفتاح
    LaunchedEffect(Unit) { if (KeyStore(ctx).activeConfig() == null) tab = 1 }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(title = { Text("جارفيس", fontWeight = FontWeight.Bold) })
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Default.Chat, null) }, label = { Text("الدردشة") })
                NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.Default.Key, null) }, label = { Text("المفاتيح") })
                NavigationBarItem(tab == 2, { tab = 2 }, { Icon(Icons.Default.Security, null) }, label = { Text("الصلاحيات") })
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad)) {
            when (tab) {
                0 -> ChatScreen(voice)
                1 -> KeysScreen()
                else -> PermissionsScreen()
            }
        }
    }
}

// ======================= الدردشة =======================

@Composable
private fun ChatScreen(voice: Voice) {
    val ctx = LocalContext.current
    val log by AgentSession.log.collectAsState()
    val busy by AgentSession.busy.collectAsState()
    var input by remember { mutableStateOf("") }
    var listening by remember { mutableStateOf(false) }
    var speakReplies by remember { mutableStateOf(OverlaySettings.speechEnabled(ctx)) }
    val listState = rememberLazyListState()

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, speakReplies) {
        val callback: (String) -> Unit = { if (speakReplies) voice.speak(it) }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME -> AgentSession.onFinalReply = callback
                Lifecycle.Event.ON_STOP -> AgentOverlay.instance?.takeOverSpeech()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            AgentSession.onFinalReply = callback
        }
        onDispose {
            owner.lifecycle.removeObserver(observer)
            if (AgentSession.onFinalReply === callback) AgentOverlay.instance?.takeOverSpeech()
        }
    }
    LaunchedEffect(log.size) { if (log.isNotEmpty()) listState.animateScrollToItem(log.size - 1) }

    fun send(text: String) {
        AgentSession.send(ctx, text.trim()); input = ""
    }

    Column(Modifier.fillMaxSize()) {
        if (log.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    "اطلب أي شيء على هاتفك\nمثال: «افتح واتساب وأرسل لأحمد صباح الخير»",
                    color = Color.Gray, fontSize = 15.sp,
                    modifier = Modifier.padding(24.dp)
                )
            }
        } else {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) { items(log) { LogBubble(it) } }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Switch(speakReplies, { speakReplies = it; OverlaySettings.setSpeechEnabled(ctx, it) })
            Spacer(Modifier.width(6.dp))
            Text("نطق الردود", fontSize = 13.sp, color = Color.Gray)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { AgentSession.clear() }, enabled = !busy) { Text("محادثة جديدة") }
        }

        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(if (listening) "أستمع…" else "اكتب أمرك…") },
                maxLines = 4, enabled = !busy
            )
            Spacer(Modifier.width(6.dp))
            // ميكروفون
            FilledIconButton(
                onClick = {
                    if (!Permissions.micGranted(ctx)) {
                        micLauncher.launch(Manifest.permission.RECORD_AUDIO); return@FilledIconButton
                    }
                    if (listening) { voice.stopListening(); listening = false; return@FilledIconButton }
                    voice.stopSpeaking()
                    listening = true
                    voice.listen(
                        onPartial = { input = it },
                        onResult = { listening = false; send(it) },
                        onError = { listening = false; input = ""; AgentSession.note(it) }
                    )
                },
                enabled = !busy,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = if (listening) Color(0xFFEF4444) else MaterialTheme.colorScheme.secondary
                )
            ) { Icon(if (listening) Icons.Default.MicOff else Icons.Default.Mic, "صوت") }
            Spacer(Modifier.width(6.dp))
            // إرسال / إيقاف
            if (busy) {
                FilledIconButton(
                    onClick = { AgentSession.stop() },
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFFEF4444))
                ) { Icon(Icons.Default.Stop, "إيقاف") }
            } else {
                FilledIconButton(onClick = { send(input) }, enabled = input.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.Send, "إرسال")
                }
            }
        }
    }
}

@Composable
private fun LogBubble(line: LogLine) {
    val (bg, fg, mono) = when (line.kind) {
        LogLine.Kind.USER -> Triple(Color(0xFF6D28D9), Color.White, false)
        LogLine.Kind.AGENT -> Triple(Color(0xFF26262F), Color.White, false)
        LogLine.Kind.ACTION -> Triple(Color(0xFF1E293B), Color(0xFF93C5FD), true)
        LogLine.Kind.OBSERVATION -> Triple(Color(0xFF15151B), Color(0xFF9CA3AF), true)
        LogLine.Kind.ERROR -> Triple(Color(0xFF3B1515), Color(0xFFFCA5A5), false)
    }
    val prefix = when (line.kind) {
        LogLine.Kind.ACTION -> "⚙ "
        LogLine.Kind.OBSERVATION -> "↳ "
        LogLine.Kind.ERROR -> "⚠ "
        else -> ""
    }
    val alignEnd = line.kind == LogLine.Kind.USER
    Box(Modifier.fillMaxWidth(), contentAlignment = if (alignEnd) Alignment.CenterStart else Alignment.CenterEnd) {
        Text(
            prefix + line.text,
            color = fg,
            fontSize = if (mono) 12.sp else 15.sp,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            modifier = Modifier
                .widthIn(max = 320.dp)
                .background(bg, RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

// ======================= المفاتيح =======================

@Composable
private fun KeysScreen() {
    val ctx = LocalContext.current
    val store = remember { KeyStore(ctx) }
    var provider by remember { mutableStateOf(store.getActiveProvider() ?: Provider.OPENAI) }
    var key by remember(provider) { mutableStateOf(store.getKey(provider).orEmpty()) }
    var model by remember(provider) { mutableStateOf(store.getModel(provider)) }
    var saved by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("المزوّد", fontWeight = FontWeight.Bold)
        Provider.entries.forEach { p ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                RadioButton(provider == p, { provider = p; saved = null })
                Text(p.displayName)
                if (!store.getKey(p).isNullOrBlank()) {
                    Spacer(Modifier.width(8.dp)); Text("✓ مفتاح محفوظ", color = Color(0xFF22C55E), fontSize = 12.sp)
                }
            }
        }
        OutlinedTextField(
            key, { key = it }, Modifier.fillMaxWidth(),
            label = { Text("مفتاح API") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
        )
        OutlinedTextField(
            model, { model = it }, Modifier.fillMaxWidth(),
            label = { Text("النموذج") }, singleLine = true,
            supportingText = { Text("يجب أن يدعم استدعاء الأدوات (tool calling)") }
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ProviderConfig.suggestedModels(provider).forEach { m ->
                FilterChip(model == m, { model = m }, label = { Text(m, fontSize = 11.sp) })
            }
        }
        if (provider == Provider.GEMINI) {
            Text(
                "gemini-4-argon يعمل عبر مفتاح من aistudio.google.com مع تفعيل الفوترة (Paid tier). اشتراك Gemini Pro في التطبيق منفصل ولا يشمل الـ API.",
                color = Color(0xFFF59E0B), fontSize = 12.sp
            )
        }
        Button(
            onClick = {
                store.saveKey(provider, key.trim())
                store.saveModel(provider, model.trim())
                store.setActiveProvider(provider)
                saved = "تم الحفظ. المزوّد النشط: ${provider.displayName}"
            },
            enabled = key.isNotBlank() && model.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text("حفظ وتفعيل") }
        OutlinedButton(
            onClick = {
                testing = true; saved = null
                scope.launch {
                    saved = AgentSession.testConnection(ProviderConfig(provider, key.trim(), model.trim()))
                    testing = false
                }
            },
            enabled = key.isNotBlank() && model.isNotBlank() && !testing,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (testing) "جارٍ الاختبار…" else "اختبار الاتصال") }
        saved?.let { Text(it, color = if (it.startsWith("✗")) Color(0xFFFCA5A5) else Color(0xFF22C55E)) }
        Text(
            "المفاتيح تُخزَّن مشفّرة على هاتفك فقط، وتُرسل مباشرة إلى المزوّد الذي اخترته.",
            color = Color.Gray, fontSize = 12.sp
        )
    }
}

// ======================= الصلاحيات =======================

@Composable
private fun PermissionsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var acc by remember { mutableStateOf(Permissions.accessibilityEnabled(ctx)) }
    var mic by remember { mutableStateOf(Permissions.micGranted(ctx)) }
    var root by remember { mutableStateOf<Boolean?>(null) }
    var bubbleEnabled by remember { mutableStateOf(OverlaySettings.bubbleEnabled(ctx)) }
    var effectsEnabled by remember { mutableStateOf(OverlaySettings.effectsEnabled(ctx)) }
    var visionEnabled by remember { mutableStateOf(OverlaySettings.visionEnabled(ctx)) }
    var rootDiagnostics by remember { mutableStateOf(OverlaySettings.rootDiagnosticsEnabled(ctx)) }

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { mic = it }

    // تحديث الحالة عند العودة من إعدادات النظام
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                acc = Permissions.accessibilityEnabled(ctx); mic = Permissions.micGranted(ctx)
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    fun openAppInfo() = ctx.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        PermRow("خدمة الوصول", "لقراءة الشاشة والنقر والكتابة. ضرورية.", acc) {
            ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        if (!acc) {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2214))) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("إذا ظهر «يتحكّم فيه إعداد محظور» أو «تم منع التطبيق من الوصول»:",
                        fontWeight = FontWeight.Bold, color = Color(0xFFF59E0B))
                    Text("1. اضغط الزر أدناه لفتح معلومات تطبيق جارفيس.", fontSize = 13.sp)
                    Text("2. اضغط ⋮ (أعلى الشاشة) ← «السماح بالإعدادات المقيَّدة» وأكّد ببصمتك/رمزك.", fontSize = 13.sp)
                    Text("3. ارجع واضغط «تفعيل» في خدمة الوصول ← التطبيقات التي تم تنزيلها ← جارفيس ← فعّل.", fontSize = 13.sp)
                    Text("إن لم يظهر خيار ⋮: حاول تفعيل الخدمة مرة واحدة أولاً (لتظهر رسالة المنع) ثم أعد الخطوة 1.",
                        fontSize = 12.sp, color = Color.Gray)
                    Button(onClick = { openAppInfo() }, modifier = Modifier.fillMaxWidth()) {
                        Text("فتح معلومات تطبيق جارفيس")
                    }
                }
            }
        }

        PermRow("الميكروفون", "للأوامر الصوتية.", mic) {
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF282019))) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("تحكم متقدم 1.4", fontWeight = FontWeight.Bold, color = Color(0xFFFFCC91))
                Text("ضغط مطوّل ومزدوج، سحب وإفلات، تكبير بإصبعين، تصفح القوائم، حافظة، التحكم بمستوى الصوت والوسائط، وفتح إعدادات الهاتف.", fontSize = 12.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("تشخيص الروت (قراءة فقط)", Modifier.weight(1f), fontSize = 13.sp)
                    Switch(checked = rootDiagnostics, onCheckedChange = {
                        rootDiagnostics = it
                        OverlaySettings.setRootDiagnosticsEnabled(ctx, it)
                    })
                }
                Text("لا يُنفّذ أوامر shell عشوائية، ولا يتجاوز كلمات المرور أو حماية النظام. يبدأ فقط بعد طلبك.", fontSize = 12.sp, color = Color(0xFFFFCC91))
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF14253C))) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("التحكم العائم", fontWeight = FontWeight.Bold, color = Color(0xFF63BDFF))
                Text(
                    "بعد الخروج من التطبيق تظهر فقاعة جارفيس فوق التطبيقات. اضغطها " +
                        "للكتابة أو الكلام وإيقاف المهمة. اسحبها لنقلها.",
                    fontSize = 13.sp
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("فقاعة المحادثة", Modifier.weight(1f))
                    Switch(checked = bubbleEnabled, onCheckedChange = {
                        bubbleEnabled = it
                        OverlaySettings.setBubbleEnabled(ctx, it)
                        AgentOverlay.instance?.refreshPreferences()
                    })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("وميض أزرق ومؤشر ماوس أثناء التحكم", Modifier.weight(1f), fontSize = 13.sp)
                    Switch(checked = effectsEnabled, onCheckedChange = {
                        effectsEnabled = it
                        OverlaySettings.setEffectsEnabled(ctx, it)
                        AgentOverlay.instance?.refreshPreferences()
                    })
                }
                Text(
                    if (acc) "تستعمل الفقاعة صلاحية خدمة الوصول، ولا تحتاج إذن الظهور فوق التطبيقات."
                    else "فعّل خدمة الوصول أولاً حتى تعمل الفقاعة والوميض خارج التطبيق.",
                    fontSize = 12.sp, color = Color(0xFFB4CBE8)
                )
            }
        }


        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF122C2B))) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Vision / SC — الرؤية البصرية", fontWeight = FontWeight.Bold, color = Color(0xFF70E1D2))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("إرسال صور الشاشة لجارفيس أثناء المهمة", Modifier.weight(1f), fontSize = 13.sp)
                    Switch(checked = visionEnabled, onCheckedChange = {
                        visionEnabled = it
                        OverlaySettings.setVisionEnabled(ctx, it)
                        // A running task reads preference at the next screenshot.
                    })
                }
                Text(
                    "عند تفعيلها، يلتقط جارفيس صورة عند استدعاء read_screen ويرسلها مباشرةً " +
                        "إلى مزوّد الذكاء الاصطناعي المحدد. لا تُحفظ الصور على الهاتف. " +
                        "قد تحتوي صور الشاشة على بيانات شخصية أو كلمات مرور، وقد تُحتسب تكلفة للصور. " +
                        "تحتاج Android 11 أو أحدث؛ على الأنظمة الأقدم يبقى وصف الشاشة النصي متاحاً.",
                    fontSize = 12.sp, color = Color(0xFFB6D7D0)
                )
            }
        }

        PermRow(
            "صلاحية الروت (اختيارية)",
            when (root) {
                false -> "الهاتف غير مروّت. لا مشكلة: جارفيس يعمل بدونها، فقط أوامر النظام المتقدمة (run_shell) معطّلة."
                true -> "مفعّلة: أوامر النظام المتقدمة متاحة."
                null -> "تفيد فقط في الهواتف المروّتة (Magisk/KernelSU)."
            },
            ok = root == true, optional = true,
            buttonText = if (root == null) "فحص" else "إعادة الفحص"
        ) { scope.launch { root = RootShell.isAvailable() } }

        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1A2230))) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("لهواتف Xiaomi / HyperOS (حتى لا يُوقف النظام الخدمة):",
                    fontWeight = FontWeight.Bold, color = Color(0xFF93C5FD))
                Text("• معلومات التطبيق ← «التشغيل التلقائي» ← فعّل.", fontSize = 13.sp)
                Text("• معلومات التطبيق ← «توفير البطارية» ← «بدون قيود».", fontSize = 13.sp)
                Text("• في قائمة التطبيقات الأخيرة: اسحب جارفيس للأسفل واضغط القفل 🔒.", fontSize = 13.sp)
                OutlinedButton(onClick = { openAppInfo() }, modifier = Modifier.fillMaxWidth()) {
                    Text("فتح معلومات التطبيق")
                }
            }
        }
    }
}

@Composable
private fun PermRow(
    title: String, desc: String, ok: Boolean,
    buttonText: String = "تفعيل", optional: Boolean = false, onClick: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                when { ok -> Icons.Default.CheckCircle; optional -> Icons.Default.Info; else -> Icons.Default.ErrorOutline },
                null,
                tint = when { ok -> Color(0xFF22C55E); optional -> Color.Gray; else -> Color(0xFFF59E0B) }
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold)
                Text(desc, fontSize = 12.sp, color = Color.Gray)
            }
            if (!ok) TextButton(onClick = onClick) { Text(buttonText) }
        }
    }
}
