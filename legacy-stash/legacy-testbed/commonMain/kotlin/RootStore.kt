import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import io.grpc.ManagedChannelBuilder

internal class RootStore {

    var state: RootState by mutableStateOf(initialState())
        private set

    private fun initialState(): RootState {
        val port = 9008
        val logLines = mutableListOf<String>();
        val logbuffer = String()
        return RootState(port,logLines,logbuffer);//,channel,client,null, boundRect{top=0;left=0;right=800;bottom=800},dumpText="")
    }

    fun print(line:String){
        state.logLines.add(line);
        println(""+line.length+":"+line)
    }
    data class RootState(
        val port: Int = 9008,
        val logLines:MutableList<String>,
        var logbuffer:String
    );
}