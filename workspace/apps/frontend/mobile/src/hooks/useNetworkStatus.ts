// 网络状态（契约⑤）：@react-native-community/netinfo 订阅 + 启动即时探测。
// offline 判定：连接断开或互联网不可达（isConnected/isInternetReachable 显式 false 才判断网，
// null（探测中）按在线处理，避免启动闪烁）。独立文件而非并入 hooks.ts：
// netinfo 原生模块只在 App 壳消费，tests/hooks.ts 链路保持零 netinfo 依赖。
import { useEffect, useState } from 'react';
import NetInfo from '@react-native-community/netinfo';
import type { NetInfoState } from '@react-native-community/netinfo';

function evaluate(state: Pick<NetInfoState, 'isConnected' | 'isInternetReachable'>): boolean {
  return state.isConnected === false || state.isInternetReachable === false;
}

/** 当前是否离线（true = 断网，App 顶部显示离线横幅）。 */
export function useNetworkStatus(): boolean {
  const [offline, setOffline] = useState(false);
  useEffect(() => {
    const handler = (state: NetInfoState) => setOffline(evaluate(state));
    const unsubscribe = NetInfo.addEventListener(handler);
    void NetInfo.fetch()
      .then(handler)
      .catch(() => undefined);
    return () => unsubscribe();
  }, []);
  return offline;
}
