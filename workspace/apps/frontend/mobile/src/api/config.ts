/**
 * 移动端 API 基址（login-app）：Expo 原生 EXPO_PUBLIC_* 环境变量注入（SDK 49+）。
 * 真机/模拟器联调：EXPO_PUBLIC_API_BASE=http://<开发机 LAN IP>:9888 npx expo start；
 * 缺省 localhost 适用于模拟器与开发机同机的场景。
 */
export const API_BASE = process.env.EXPO_PUBLIC_API_BASE ?? 'http://localhost:9888';
