import com.infamousvague.devkeyboard.OtpCodes
import com.infamousvague.devkeyboard.OtpCodesTable

/** tool/otp_check.sh: the code finder against its table, with no Gradle. Also: a message as an argument, and what it finds. */
fun main(args: Array<String>) {
    if (args.isNotEmpty()) {
        for (message in args) println("${OtpCodes.find(message)}  <-  $message")
        return
    }
    var wrong = 0
    for ((message, code) in OtpCodesTable.MESSAGES) {
        val found = OtpCodes.find(message)
        if (found != code) { wrong++; println("WRONG  ${message.take(90).replace('\n', ' ')}\n       found $found, not $code") }
    }
    val codes = OtpCodesTable.MESSAGES.count { it.second != null }
    println("${OtpCodesTable.MESSAGES.size} messages ($codes with a code, ${OtpCodesTable.MESSAGES.size - codes} without): ${if (wrong == 0) "all right" else "$wrong wrong"}")
    if (wrong != 0) kotlin.system.exitProcess(1)
}
