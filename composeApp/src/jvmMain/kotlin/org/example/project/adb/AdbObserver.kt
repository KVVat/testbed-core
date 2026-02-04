package org.example.project.adb


import com.malinskiy.adam.exception.RequestRejectedException
import com.malinskiy.adam.request.shell.v1.ShellCommandRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.example.project.adb.rules.AdbDeviceRule

class AdbObserver(){//_viewModel: AppViewModel){
    //val viewModel = _viewModel
    var adb: AdbDeviceRule = AdbDeviceRule()
    var adbProps:AdbProps = AdbProps()

    suspend fun observeAdb():Boolean{
        try {
            adb.startAlone()
            while (true) {
                withContext(Dispatchers.IO) {
                    Thread.sleep(250)
                }
                //if (viewModel.uiState.value.isRunning) continue
                val isDeviceInit = adb.isDeviceInitialised()
                try {
                    if (isDeviceInit) {
//                        if (!viewModel.uiState.value.adbIsValid) {
//                           //logging("Device Connected > ${adb.deviceSerial}/${adb.displayId}")
//                            adbProps = AdbProps(
//                                adb.osversion,
//                                adb.productmodel,
//                                adb.deviceSerial,
//                                adb.displayId
//                            )
//                        }
            //            viewModel.toggleAdbIsValid(true)
                        adb.adb.execute(ShellCommandRequest("echo"))
                    }
                } catch (rrException: RequestRejectedException) {
                    adb.startAlone()
                    continue
                }
            }
        } catch (anyException:Exception){
            //disable if flag is enabled
//            if(viewModel.uiState.value.adbIsValid) {
//                //logging("Device Disconnected > (" + anyException.localizedMessage+") #${anyException.javaClass.name}")
//                adbProps = AdbProps()
//                viewModel.toggleAdbIsValid(false)
//            }
        }
        return true
    }
}