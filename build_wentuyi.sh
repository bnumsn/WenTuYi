#!/bin/bash
# 文图易 (Wentuyi) 一键构建脚本

echo "--- [1/3] 正在检测 Android SDK 路径 ---"
SDK_PATHS=(
    "/home/user/Android/Sdk"
    "/usr/lib/android-sdk"
    "/opt/android-sdk"
    "/usr/local/android-sdk"
)

FOUND_SDK=""
for p in "${SDK_PATHS[@]}"; do
    if [ -d "$p" ]; then
        FOUND_SDK="$p"
        break
    fi
done

if [ -z "$FOUND_SDK" ]; then
    echo "❌ 错误：未找到 Android SDK。请手动设置 ANDROID_HOME 环境变量。"
    exit 1
fi

echo "✅ 找到 SDK: $FOUND_SDK"
echo "sdk.dir=$FOUND_SDK" > local.properties

echo "--- [2/3] 正在执行 Gradle 编译 ---"
# 如果没有本地 gradle，则尝试使用 gradle wrapper (假设已生成) 或系统 gradle
if [ -f "./gradlew" ]; then
    chmod +x ./gradlew
    ./gradlew assembleDebug
elif command -v gradle &> /dev/null; then
    gradle assembleDebug
else
    echo "❌ 错误：未找到 Gradle 环境。请安装 gradle。"
    exit 1
fi

echo "--- [3/3] 编译结果 ---"
APK_PATH="./app/build/outputs/apk/debug/app-debug.apk"
if [ -f "$APK_PATH" ]; then
    echo "🎉 恭喜！文图易输入法编译成功！"
    echo "📍 APK 位置：$(realpath $APK_PATH)"
    echo "💡 提示：你可以通过 adb install $APK_PATH 直接安装到手机。"
else
    echo "❌ 编译失败，请检查上方日志。"
fi
