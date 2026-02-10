import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Card
import androidx.compose.material.Checkbox
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextField
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.android.certifications.junit.JUnitTestRunner
import com.android.certifications.junit.xmlreport.AntXmlRunListener
import com.android.certifications.test.rule.AdbDeviceRule
import com.android.certifications.test.utils.AdamUtils
import com.android.certifications.test.utils.SFR
import com.android.certifications.test.utils.ShellRequestThread
import com.android.certifications.test.utils.output_path
import com.android.certifications.test.utils.resource_path
import com.darkrockstudios.libraries.mpfilepicker.DirectoryPicker
import com.malinskiy.adam.AndroidDebugBridgeClient
import com.malinskiy.adam.AndroidDebugBridgeClientFactory
import com.malinskiy.adam.exception.RequestRejectedException
import com.malinskiy.adam.interactor.StartAdbInteractor
import com.malinskiy.adam.request.device.ListDevicesRequest
import com.malinskiy.adam.request.forwarding.ListPortForwardsRequest
import com.malinskiy.adam.request.forwarding.LocalTcpPortSpec
import com.malinskiy.adam.request.forwarding.PortForwardRequest
import com.malinskiy.adam.request.forwarding.PortForwardingMode
import com.malinskiy.adam.request.forwarding.PortForwardingRule
import com.malinskiy.adam.request.forwarding.RemoteTcpPortSpec
import com.malinskiy.adam.request.pkg.PmListRequest
import com.malinskiy.adam.request.reverse.ReversePortForwardRequest
import com.malinskiy.adam.request.shell.v1.ShellCommandRequest
import com.russhwolf.settings.PreferencesSettings
import com.russhwolf.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Paths
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Properties
import java.util.logging.FileHandler
import java.util.logging.Level
import java.util.logging.Logger
import java.util.logging.SimpleFormatter
import java.util.prefs.Preferences


data class TestCase(
    val testClass:String
){
    val clazz = Class.forName(testPackage + "." + testClass)

    var sfr =clazz.getAnnotation(SFR::class.java)
    var title="Title";
    var description="Dummy";

    init {
        if(sfr == null){
            sfr =SFR(testClass,"Dummy Description")
        }
        title = sfr.title.trimIndent()
        description = sfr.description.trimIndent()
    }
}

val testPackage = "com.android.certifications.test"
val testCases = listOf(
    TestCase("FDP_ACF_EXT"),
    TestCase("FPR_PSE1"),
    TestCase("FDP_ACC1"),
    TestCase("KernelAcvpTest"),
    TestCase("FCS_CKH_EXT1"),
    TestCase("FTP_ITC_EXT1"),
    TestCase("MuttonTest"),
    )


//static final Logger logger = Logger.getLogger(Logging.class.getName());
val logger = Logger.getLogger("TestBed");
//Log Utils which supports to record data in textarea from outside of the compose.
@Stable
class LogConsole(val myLogger:MutableStateFlow<String>,
                  val coroutineScope: CoroutineScope,val loggerText:String,
                  val textStack:MutableList<String> = mutableListOf()
){
    fun clear(){
        coroutineScope.launch {
            textStack.clear();
            myLogger.emit("");
        }
    }
}
lateinit var console:LogConsole;
val flowLogger = MutableStateFlow("")
var antRunner:AntXmlRunListener? = null
fun logging(line: String){
   console.coroutineScope.launch {
       console.textStack.add(line);
       if(console.textStack.size>500)
           console.clear()
       console.myLogger.emit(console.textStack.joinToString("\r\n"));
       logger.info(line);
       println(line);
       antRunner?.appendSystemOut(line)
       //println(antRunner?.javaClass?.kotlin?.simpleName)

    }
}
// manage setting dialogue visibility from outside composable
val flowVisibleDialog = MutableStateFlow(false)
data class AdbProps(val osVersion:String, val model:String,val serial:String, val displayId:String)

@OptIn(ExperimentalTextApi::class,ExperimentalMaterialApi::class)
@Composable
@Preview
fun App(settings: Settings) {

    val sc by remember { mutableStateOf(settings) }
    var ap by remember { mutableStateOf(AdbProps("","","","")) }
    val coroutineScope = rememberCoroutineScope()
    val loggerText by flowLogger.collectAsState("")
    var isTestRunning by remember { mutableStateOf(false) }
    var isServerRunning by remember { mutableStateOf(false)  }

    //Behaviour
    var resourcePath by remember { mutableStateOf(sc.getString("PATH_RESOURCE",""))  }
    var outputPath by remember { mutableStateOf(sc.getString("PATH_OUTPUT","")) }
    var useEmbedResource by remember { mutableStateOf(sc.getBoolean("USE_EMBED_RES",true)) }
    var fileHandler:FileHandler? = null;//FileHandler(outputPath, false)
    val serverShell by remember { mutableStateOf(ShellRequestThread())  }


    fun validateSettings():Boolean{
        return File(resourcePath).isDirectory && File(outputPath).isDirectory
    }
    fun updateLogger(){
        if(fileHandler !== null) logger.removeHandler(fileHandler)
        var newfilepath:String
            = Paths.get(outputPath,"testbed.log").toAbsolutePath().toString();
        logging(newfilepath);
        fileHandler =
            FileHandler(newfilepath, false)

        fileHandler!!.formatter = SimpleFormatter()
        //java.util.logging.SimpleFormatter.format="%4$s: %5$s [%1$tc]%n"
        logger.addHandler(fileHandler)
        logger.level = Level.FINE
    }
    val isSettingOpen by flowVisibleDialog.collectAsState(false)

    console = remember { LogConsole (flowLogger,coroutineScope,loggerText) }
    var adbIsValid by remember { mutableStateOf(false) }
    lateinit var adb:AdbDeviceRule

    suspend fun observeAdb():Boolean{
        try {
            //adb = AdbDeviceRule()
            val client = adb.adb
            adb.startAlone()
            while(true){
                withContext(Dispatchers.IO) {
                    Thread.sleep(1000)
                }
                if (isTestRunning) continue
                val initialised = adb.isDeviceInitialised()
                try {
                    if (initialised) {
                        if (!adbIsValid) {
                            logging("Device Connected > ${adb.deviceSerial}/${adb.displayId}")
                            ap =
                                AdbProps(
                                    adb.osversion,
                                    adb.productmodel,
                                    adb.deviceSerial,
                                    adb.displayId
                                )
                        }
                        adbIsValid = true
                        //Hopefully finish gently but it raises exception..
                        client.execute(ShellCommandRequest("echo"))
                    }
                } catch (anyException: Exception) {
                    if (anyException is RequestRejectedException) {
                        adb.startAlone()
                        continue
                    } else {
                        throw anyException
                    }
                }
            }

        } catch (exception:Exception){
            if(adbIsValid) {
                logging("Device Disconnected > (" + exception.localizedMessage+") #${exception.javaClass.name}")
                ap = AdbProps("", "", "", "")
                adbIsValid = false
            }
        }

        return true
    }


    suspend fun evalPortForward(adb:AdbDeviceRule):Boolean{
        val rules: List<PortForwardingRule> = adb.adb.execute(
            ListPortForwardsRequest(adb.deviceSerial)
        )
        for(r in rules){
            val param = r.localSpec.toSpec()+":"+r.remoteSpec.toSpec();
            if(param.equals("tcp:9008:tcp:9008"))
                return true
        }
        return false
    }
    suspend fun evalPackages(verifies:List<String>,adb:AdbDeviceRule):Boolean{
        val packages: List<com.malinskiy.adam.request.pkg.Package> = adb.adb.execute(
            request = PmListRequest(
                includePath = false
            ),
            adb.deviceSerial
        )
        var found =0;
        run loop@{
            packages.forEach {
                verifies.forEach { v->
                    if(it.name.equals(v)){
                        found++
                    }
                }
                if(found == verifies.size)
                    return@loop//break
            }
        }
        if(found == verifies.size){
            logging("found all prerequisite packages")
            return true
        } else {
            logging("some of prerequisite packages not found")
            return false
        }

    }

    suspend fun runAutomataServer():Boolean{

        val INSTRUMENT_PACKAGE =
            "com.github.uiautomutton.test/androidx.test.runner.AndroidJUnitRunner"
        val file_server: File =
            File(Paths.get(resource_path(),"muttons","uiserver-release.apk").toUri())
        val file_instrument: File =
            File(Paths.get(resource_path(),"muttons","uiserver-release-androidTest.apk").toUri())

        serverShell.setShellCommand("am instrument -w $INSTRUMENT_PACKAGE",adb)
        if(!serverShell.isInitialized()){
            logging("Server is not initialized")
        }
        //Install Packages Here
        val packagesFound =
            evalPackages(listOf("com.github.uiautomutton","com.github.uiautomutton.test"),adb)

        if(!packagesFound){
            AdamUtils.InstallApk(file_server,true,adb);
            delay(500)
            AdamUtils.InstallApk(file_instrument,true,adb);
            delay(500)
        }
        //Check PortForward
        if(!evalPortForward(adb)){
            adb.adb.execute(
                PortForwardRequest(
                    remote = RemoteTcpPortSpec(9008),
                    local= LocalTcpPortSpec(9008),
                    mode = PortForwardingMode.DEFAULT,
                    serial = adb.deviceSerial
                )
            )
            delay(500)

            if(!evalPortForward(adb))//If command failed
                return false
        }
        serverShell.run()
        return true
    }

    //Launch Event
    LaunchedEffect(Unit) {
        updateLogger()
        logging("application launched.")
        if(!validateSettings()){
            //isSettingOpen = false
            flowVisibleDialog.emit(true)
        }
        adb = AdbDeviceRule()
        //text = String(resource("welcome.txt").readBytes())
        launch {
            withContext(Dispatchers.IO) {
                while(true){
                    observeAdb()
                }
            }
        }
    }


    MaterialTheme {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.background(Color(0xFFEEEEEE))) {
                Column(modifier = Modifier.fillMaxWidth(fraction = 0.3f)){
                    LazyColumn( modifier = Modifier.fillMaxHeight(fraction = 0.9f).fillMaxWidth()) {
                        items(testCases) {
                            Card(enabled = !isTestRunning,
                                backgroundColor = if(isTestRunning) Color.LightGray else Color.White,
                                modifier = Modifier.padding(1.dp).height(120.dp).fillParentMaxWidth()
                                    .padding(4.dp),
                                onClick = {

                                    if(!adbIsValid){
                                        logging("*** Need to connect a device to run the test cases.")
                                        return@Card;
                                    }

                                    isTestRunning = true;
                                    console.clear()
                                    logging("[[${it.title}]]")

                                    val clazz = Class.forName(testPackage + "." + it.testClass)
                                    var sfr =clazz.getAnnotation(SFR::class.java)
                                    if(sfr == null){
                                        sfr = SFR("title","description","shortname")
                                    }

                                    val testProps = Properties()
                                    testProps.setProperty("SFR.name",sfr.title)
                                    testProps.setProperty("SFR.description",sfr.description)
                                    if(!ap.osVersion.equals("")){
                                      testProps.setProperty("device", ap.model)
                                      testProps.setProperty("osversion", ap.osVersion)
                                      testProps.setProperty("system", ap.displayId)
                                      testProps.setProperty("signature", ap.serial)
                                    }
                                    //adb.osversion,adb.productmodel,adb.deviceSerial,adb.displayId
                                    antRunner =  AntXmlRunListener(::logging, testProps) {
                                        isTestRunning = false;
                                    }
                                    val now = SimpleDateFormat("yyyyMMddHHmmss").format(Date())
                                    antRunner!!.setOutputStream(
                                        FileOutputStream(
                                        Paths.get(output_path(),"junit-report-${sfr.shortname}-$now.xml").toFile())
                                    )

                                    val runner = JUnitTestRunner(arrayOf(clazz),antRunner)

                                    runner.start()
                                },
                            ) {
                                Column {
                                    Text(
                                        text = it.title,fontFamily = FontFamily.Monospace,
                                        fontSize = 14.sp, fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(5.dp).fillMaxWidth()
                                    )
                                    Text(
                                        text = it.description, overflow = TextOverflow.Ellipsis,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp, maxLines = 6,
                                        modifier = Modifier.padding(5.dp).fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }
                    Column(modifier = Modifier.fillMaxSize().background(color = Color.Transparent).padding(10.dp)) {
                        Row(modifier = Modifier.padding(4.dp)){
                        Button(modifier = Modifier.requiredSize(50.dp)
                            , onClick = {
                                //isSettingOpen.em = true
                                coroutineScope.launch {
                                    flowVisibleDialog.emit(true);
                                }
                            }, content = {
                                Text("⚙", fontSize =18.sp)
                            }
                        )
                        Spacer(Modifier.size(6.dp))
                        Button(modifier = Modifier.requiredHeight(50.dp), enabled = adbIsValid, colors =
                            if(isServerRunning) ButtonDefaults.buttonColors(backgroundColor = Color.Green)
                                else ButtonDefaults.buttonColors(backgroundColor = Color.White)
                            , onClick = {
                                coroutineScope.launch {
                                    withContext(Dispatchers.IO) {
                                        //automata start processes
                                        adb = AdbDeviceRule()
                                        adb.startAlone()
                                        if(serverShell.isInitialized()){
                                            if(serverShell.isRunning){
                                                serverShell.interrupt()

                                                isServerRunning=false;
                                                return@withContext
                                            }
                                        }
                                        isServerRunning=true
                                        runAutomataServer()
                                    }
                                }
                            }, content = {
                                Text(text = if(isServerRunning) "Stop UI Server" else "Start UI Server",
                                    fontSize =10.sp)
                            }
                        )
                        }
                    }
                }
                //
                SelectionContainer {
                    LogText(loggerText)
                    Text(if(adbIsValid) "🟢" else "🛑", modifier = Modifier.background(Color.Transparent)
                        , color = Color.Black
                    )
                }
            }
        }
    }



    if(isSettingOpen) {
        // Path Setting Dialogue Implementation


        var dlgmessage by remember { mutableStateOf("") }
        fun onSettingDialogClose() {
            logging("Folder Setting is dismissed")
            dlgmessage=""
            if(validateSettings()) {
                //settingConfig.["PATH_OUTPUT"]=outputPath
                coroutineScope.launch {
                    flowVisibleDialog.emit(false)
                    //persist values to property file
                    settings.putString("PATH_RESOURCE",resourcePath)
                    settings.putString("PATH_OUTPUT",outputPath)
                    settings.putBoolean("USE_EMBED_RES",useEmbedResource)
                }



            } else {
                dlgmessage = "⚠️Need to set existent path."
            }
        }

        Dialog(
            onDismissRequest = {
                onSettingDialogClose()
            },
        ){
            var showDirPicker by remember { mutableStateOf(false) }
            var targetDir by remember { mutableStateOf(0) }

            DirectoryPicker(showDirPicker) { path ->
                showDirPicker = false
                // do something with path
                if(targetDir==0){
                    resourcePath=path!!
                } else if(targetDir==1){
                    outputPath=path!!
                }
            }

            StandardOneButtonDialog("Folder Settings","OK",
                onCloseRequest = {
                    onSettingDialogClose()
                }){
                Column {
                    Text("Resource Folder", modifier = Modifier.fillMaxWidth())
                    //
                    Row(verticalAlignment = Alignment.CenterVertically){
                        TextField(value=resourcePath, onValueChange = { resourcePath=it },
                            enabled=!useEmbedResource, modifier = Modifier.fillMaxWidth(0.85f))
                        Spacer(Modifier.size(6.dp))
                        Button(modifier = Modifier.requiredSize(60.dp)
                            , onClick = {
                                targetDir=0
                                showDirPicker=true
                            }, content = {
                                Text("\uD83D\uDCC1", fontSize =18.sp)
                            }, enabled = !useEmbedResource
                        )
                    }
                    //
                    LabelledCheckBox(checked = useEmbedResource, onCheckedChange = {
                        useEmbedResource=it
                        if(it){
                            resourcePath = System.getProperty("compose.application.resources.dir")
                        }
                    },label="Use Embedded Resources" )
                    //
                    Text("Output Folder", modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically){
                        TextField(value=outputPath, onValueChange = { outputPath=it },
                            enabled=true, modifier = Modifier.fillMaxWidth(0.85f))
                        Spacer(Modifier.size(6.dp))
                        Button(modifier = Modifier.requiredSize(60.dp)
                            , onClick = {
                                targetDir=1
                                showDirPicker=true
                            }, content = {
                                Text("\uD83D\uDCC1", fontSize =18.sp)
                            }
                        )
                    }
                    Text(dlgmessage, modifier = Modifier.fillMaxWidth())
                }
            }
            if(useEmbedResource == true){
                resourcePath = System.getProperty("compose.application.resources.dir")
            }
        }
    }
}



@Composable
fun LabelledCheckBox(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit),
    label: String,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .clickable(
                indication = rememberRipple(color = MaterialTheme.colors.primary),
                interactionSource = remember { MutableInteractionSource() },
                onClick = { onCheckedChange(!checked) }
            )
            .requiredHeight(ButtonDefaults.MinHeight)
            .padding(4.dp)
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = null
        )

        Spacer(Modifier.size(6.dp))

        Text(
            text = label,
        )
    }
}
@Composable
fun StandardOneButtonDialog(title:String="Title",
                          button1Text:String="Apply",
                          onCloseRequest:()->Unit,
                          content: @Composable () -> Unit){
    Card {
        Column(modifier = Modifier.background(Color.White)) {
            Row(Modifier.background(Color.White).padding(10.dp)) {
                Text(
                    title, color = Color.Black,
                    fontSize = 20.sp, fontWeight = FontWeight.Bold
                )
            }
            Row(modifier = Modifier.fillMaxWidth().padding(start=10.dp, end = 10.dp, bottom = 10.dp)) {
                content()
            }
            Row(modifier = Modifier.align(Alignment.End).padding(10.dp)) {
                Button(onClick = {
                    onCloseRequest()
                }) {
                    Text(button1Text)
                }
            }
        }
    }
}
@Composable
fun LogText(text: String) {

    val logState = rememberScrollState(0)

    LaunchedEffect(text) {
        logState.animateScrollTo(logState.maxValue)
    }

    //Can change colors each lines?
    //https://stackoverflow.com/questions/72832802/how-to-show-multiple-color-text-in-same-text-view-with-jetpack-compose

    Text(
        text = text, color = Color.Green, fontFamily = FontFamily.Monospace,
        lineHeight = 20.sp, fontSize = 12.sp,
        modifier = Modifier.fillMaxWidth(fraction = 1.0f)
            .fillMaxHeight().background(Color.Black).padding(12.dp)
            .verticalScroll(logState)
    )
}





fun main() = application {

    //val isDialogOpen by flowSettingDialogOpen.collectAsState(false)

    val state = rememberWindowState(
        position = WindowPosition(Alignment.Center), size = DpSize(1024.dp, 600.dp)
    )
    val windowTitle by remember { mutableStateOf("ADSRP Test Application") }

    val preferences = Preferences.userRoot()
    val settings = PreferencesSettings(preferences)


    Window(title=windowTitle,onCloseRequest = ::exitApplication, undecorated = false, state = state) {
        App(settings)
        MenuBar {
            Menu("File", mnemonic = 'F') {
                Item(
                    "Settings",
                    onClick = { runBlocking {
                        //Verify the ADB server is running
                    } }

                )
                Item(
                    "Adb Doctor",
                    onClick = { runBlocking {
                        //Verify the ADB server is running
                        val found = StartAdbInteractor().execute()
                        if (found) {
                            //Create AndroidDebugBridgeServer instance
                            val adb = AndroidDebugBridgeClientFactory().apply {
                                coroutineContext = Dispatchers.IO
                            }.build()
                            //Execute requests using suspendable execute() methods. First list available devices
                            val devices = adb.execute(request = ListDevicesRequest())
                            try {
                                val serial = devices.first().serial
                                //Execute an actual command specifying serial number of device
                                val output = adb.execute(
                                    ShellCommandRequest("echo hello"),
                                    serial = serial
                                )
                                println(output.output)
                            } catch (ex: Exception) {
                                println(ex.message)
                            }
                        } else {
                            println("not found")
                        }
                    } }
                )
            }
        }
    }
}
