package io.github.k1midi0.infinitesimal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.text.BasicText

/**
 * 安卓端的入口。
 *
 * 这一端后置：模块留在仓库里，构筑时先在 settings.gradle.kts 里把它 include 回来。
 * 界面上只用得到 foundation 的 [BasicText]——这个模块的依赖是刻意留少的，material3
 * 没有声明，所以这里不是 material3 的 Text。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            BasicText("无限小")
        }
    }
}
