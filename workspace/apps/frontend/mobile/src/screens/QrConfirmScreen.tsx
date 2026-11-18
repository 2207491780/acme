import { useCallback, useMemo, useRef, useState } from 'react';
import { StyleSheet, Text, TextInput, View } from 'react-native';
import { CameraView, useCameraPermissions, type BarcodeScanningResult } from 'expo-camera';
import { formatDate, type NeutralPalette } from '@acme/core';
import { Button, Card, Loading, Muted, Title, toast } from '../components/ui';
import { ApiError } from '../api/client';
import { authApi, type QrTicketInfoView, type QrTicketStatus } from '../api/auth';
import { useBSessionStore } from '../store/bSessionStore';
import { useAppTheme } from '../theme';
import { useTranslation } from '../i18n';
import { useNavigation } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import type { RootStackParamList } from '../nav';

/** 票据状态 → 复用 web 扫码域文案键（跨端语义一致；静态映射禁动态拼键）。 */
const STATUS_KEY: Record<QrTicketStatus, string> = {
  created: 'login.qr.statusCreated',
  scanned: 'login.qr.statusScanned',
  confirmed: 'login.qr.statusConfirmed',
  expired: 'login.qr.statusExpired',
  cancelled: 'login.qr.statusCancelled',
};

/** 扫码确认屏（REQ-20261119-040838 AC-8，Stack 屏 QrConfirm）：
 *  相机（expo-camera）扫 Web 登录页二维码 → 解析 qrPayload JSON{t: ticketId} → qrInfo
 *  （B 令牌，created→scanned）→ 展示 status/createdAt → 「确认登录」qrConfirm → 成功 toast
 *  + 返回；「取消登录」qrCancel。相机权限被拒/扫码失败 → 手动输入 ticketId 备选入口。
 *  前置：B 端员工会话（LoginScreen 账密登录）；无会话仅展示引导，不发起请求。 */
export function QrConfirmScreen() {
  const { t } = useTranslation();
  const { palette } = useAppTheme();
  const navigation = useNavigation<NativeStackNavigationProp<RootStackParamList>>();
  const bSession = useBSessionStore((s) => s.session);
  const [permission, requestPermission] = useCameraPermissions();
  const [ticketId, setTicketId] = useState('');
  const [info, setInfo] = useState<QrTicketInfoView | null>(null);
  const [infoError, setInfoError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  // 相机会连续回调同一帧：一票只处理一次，避免重复 info 请求
  const handledRef = useRef(false);
  const styles = useMemo(() => stylesFor(palette), [palette]);

  const failInfo = useCallback((ex: unknown) => {
    setInfoError(ex instanceof ApiError ? ex.message : t('login.mobile.qrInfoFailed'));
  }, [t]);

  const loadInfo = useCallback((id: string) => {
    const tid = id.trim();
    if (!tid) {
      setInfoError(t('login.mobile.qrPayloadInvalid'));
      return;
    }
    setInfoError(null);
    authApi.qrInfo(tid)
      .then((view) => {
        handledRef.current = true;
        setInfo(view);
      })
      .catch(failInfo);
  }, [failInfo, t]);

  /** 相机扫码回调：解析 qrPayload JSON{t}（契约⑤），非法载荷提示不请求。 */
  const handleBarcode = useCallback((event: BarcodeScanningResult) => {
    if (handledRef.current) {
      return;
    }
    try {
      const payload = JSON.parse(event.data) as { t?: unknown };
      if (typeof payload.t === 'string' && payload.t.length > 0) {
        setTicketId(payload.t);
        loadInfo(payload.t);
      } else {
        setInfoError(t('login.mobile.qrPayloadInvalid'));
      }
    } catch {
      setInfoError(t('login.mobile.qrPayloadInvalid'));
    }
  }, [loadInfo, t]);

  const confirm = useCallback(() => {
    const tid = ticketId.trim();
    if (!tid || busy) {
      return;
    }
    setBusy(true);
    setInfoError(null);
    authApi.qrConfirm(tid)
      .then(() => {
        toast(t('login.mobile.qrConfirmOk'), 'success');
        navigation.goBack();
      })
      .catch(failInfo)
      .finally(() => setBusy(false));
  }, [busy, failInfo, navigation, t, ticketId]);

  const cancel = useCallback(() => {
    const tid = ticketId.trim();
    if (!tid || busy) {
      return;
    }
    setBusy(true);
    setInfoError(null);
    authApi.qrCancel(tid)
      .then(() => {
        toast(t('login.mobile.qrCancelOk'), 'muted');
        navigation.goBack();
      })
      .catch(failInfo)
      .finally(() => setBusy(false));
  }, [busy, failInfo, navigation, t, ticketId]);

  const cameraReady = bSession != null && permission?.granted === true;
  const permissionPending = bSession != null && permission == null;

  return (
    <Card style={styles.card}>
      <Title>{t('login.mobile.qrTitle')}</Title>
      <Muted>{t('login.mobile.qrScanHint')}</Muted>
      {infoError ? (
        <View style={styles.banner} testID="qr-info-error">
          <Text style={[styles.bannerText, { color: palette.text2 }]}>{infoError}</Text>
        </View>
      ) : null}
      {!bSession ? (
        <Muted>{t('login.mobile.qrNeedEmployeeSession')}</Muted>
      ) : permissionPending ? (
        <Loading />
      ) : cameraReady ? (
        <View style={styles.cameraBox} testID="camera-box">
          <CameraView
            style={styles.camera}
            barcodeScannerSettings={{ barcodeTypes: ['qr'] }}
            onBarcodeScanned={handleBarcode}
          />
        </View>
      ) : (
        <View style={styles.deniedBox} testID="camera-denied">
          <Muted>{t('login.mobile.qrCameraDenied')}</Muted>
          <Button
            label={t('login.mobile.qrCameraRequest')}
            variant="ghost"
            small
            onPress={() => void requestPermission()}
            testID="camera-request"
          />
        </View>
      )}
      {/* 手动输入备选（相机权限被拒/扫码失败时可用） */}
      <Muted>{t('login.mobile.qrManualTicket')}</Muted>
      <TextInput
        value={ticketId}
        onChangeText={setTicketId}
        placeholder={t('login.mobile.qrTicketPlaceholder')}
        placeholderTextColor={palette.muted}
        autoCapitalize="none"
        autoCorrect={false}
        accessibilityLabel={t('login.mobile.qrTicketPlaceholder')}
        testID="ticket-input"
        style={styles.input}
      />
      <Button
        label={t('login.mobile.qrLoadInfo')}
        variant="ghost"
        small
        onPress={() => loadInfo(ticketId)}
        testID="qr-load-info"
      />
      {info ? (
        <View style={styles.infoBox} testID="qr-info">
          <Text style={[styles.infoLine, { color: palette.text }]}>
            {t('login.mobile.qrStatus')}：{t(STATUS_KEY[info.status])}
          </Text>
          <Text style={[styles.infoLine, { color: palette.text2 }]}>
            {t('login.mobile.qrCreatedAt')}：{formatDate(info.createdAt)}
          </Text>
          <Muted>{t('login.mobile.qrWaitingConfirm')}</Muted>
          <Button
            label={t('login.mobile.qrConfirmButton')}
            onPress={confirm}
            disabled={busy}
            testID="qr-confirm"
          />
          <Button
            label={t('login.mobile.qrCancelButton')}
            variant="danger"
            onPress={cancel}
            disabled={busy}
            testID="qr-cancel"
          />
        </View>
      ) : null}
      <Button
        label={t('login.mobile.qrBack')}
        variant="ghost"
        small
        onPress={() => navigation.goBack()}
        testID="qr-back"
      />
    </Card>
  );
}

const stylesFor = (p: NeutralPalette) =>
  StyleSheet.create({
    card: { gap: 10, margin: 16 },
    banner: {
      borderRadius: 10,
      borderWidth: 1,
      borderColor: p.border,
      padding: 10,
      backgroundColor: p.surface,
    },
    bannerText: { fontSize: 12 },
    cameraBox: { height: 220, borderRadius: 12, overflow: 'hidden', backgroundColor: p.bg1 },
    camera: { flex: 1 },
    deniedBox: { gap: 8, alignItems: 'flex-start' },
    input: {
      borderWidth: 1,
      borderColor: p.border,
      borderRadius: 12,
      backgroundColor: p.surface,
      padding: 10,
      fontSize: 13,
      color: p.text,
    },
    infoBox: { gap: 8, borderWidth: 1, borderColor: p.border, borderRadius: 12, padding: 12 },
    infoLine: { fontSize: 13, fontWeight: '600' },
  });
