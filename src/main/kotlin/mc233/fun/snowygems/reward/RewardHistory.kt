package mc233.`fun`.snowygems.reward

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Base64

/** 保存成功执行时的函数配置，重载配置后拆卸也不会误撤销新配置中的奖励。 */
data class AppliedReward(val call: FunctionCall, val undoData: Map<String, String>)

/** 每颗宝石一个记录。使用有长度边界的数据格式，不反序列化任意 Java 对象。 */
object RewardHistory {
    private const val VERSION = 1
    private const val MAX_ENTRIES = 4096

    fun encode(rewards: List<AppliedReward>): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(VERSION)
            out.writeInt(rewards.size)
            rewards.forEach { reward ->
                out.writeUTF(reward.call.name)
                writeMap(out, reward.call.args)
                writeMap(out, reward.undoData)
            }
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray())
    }

    fun decode(encoded: String): List<AppliedReward> = DataInputStream(
        ByteArrayInputStream(Base64.getDecoder().decode(encoded))
    ).use { input ->
        require(input.readInt() == VERSION) { "Unsupported reward history version" }
        val count = input.readInt().also { require(it in 0..MAX_ENTRIES) }
        val rewards = List(count) {
            AppliedReward(FunctionCall(input.readUTF(), readMap(input)), readMap(input))
        }
        require(input.available() == 0) { "Trailing reward history data" }
        rewards
    }

    private fun writeMap(out: DataOutputStream, map: Map<String, String>) {
        out.writeInt(map.size)
        map.forEach { (key, value) -> out.writeUTF(key); out.writeUTF(value) }
    }

    private fun readMap(input: DataInputStream): LinkedHashMap<String, String> {
        val count = input.readInt().also { require(it in 0..MAX_ENTRIES) }
        return linkedMapOf<String, String>().apply {
            repeat(count) { put(input.readUTF(), input.readUTF()) }
        }
    }
}
