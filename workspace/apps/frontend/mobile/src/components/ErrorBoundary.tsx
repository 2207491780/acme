// 错误边界（契约④）：class 组件兜底渲染异常——fallback 卡片 + 重试（重新挂载子树）。
// 文案复用 core components.errorBoundary 域（zh/en 成对）；重试 = 清除错误态并递增 attempt
// 作为 Fragment key，强制子树全新挂载（AC-5：可重试恢复，且不影响其他 Tab）。
import { Component, createElement, Fragment } from 'react';
import type { ReactNode } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { i18n } from '@acme/core';
import { TONE_COLOR, useAppTheme } from '../theme';
import { Button } from './ui';

interface ErrorBoundaryProps {
  children: ReactNode;
}

interface ErrorBoundaryState {
  error: Error | null;
  attempt: number;
}

function FallbackCard({ onRetry }: { onRetry: () => void }) {
  const { palette } = useAppTheme();
  return (
    <View
      style={[
        styles.card,
        { backgroundColor: palette.surface, borderColor: `${TONE_COLOR.danger}66` },
      ]}
    >
      <Text style={[styles.title, { color: palette.text }]}>
        {i18n.t('components.errorBoundary.title')}
      </Text>
      <Text style={[styles.desc, { color: palette.text2 }]}>
        {i18n.t('components.errorBoundary.desc')}
      </Text>
      <Button label={i18n.t('components.errorBoundary.retry')} variant="danger" onPress={onRetry} />
    </View>
  );
}

export class ErrorBoundary extends Component<ErrorBoundaryProps, ErrorBoundaryState> {
  state: ErrorBoundaryState = { error: null, attempt: 0 };

  static getDerivedStateFromError(error: unknown): Partial<ErrorBoundaryState> {
    return { error: error instanceof Error ? error : new Error(String(error)) };
  }

  handleRetry = () => {
    this.setState((s) => ({ error: null, attempt: s.attempt + 1 }));
  };

  render() {
    const { error, attempt } = this.state;
    if (error) {
      return <FallbackCard onRetry={this.handleRetry} />;
    }
    // attempt 作为 key：重试时子树整体重新挂载（重新执行全部 effect / state 初始化）。
    return createElement(Fragment, { key: attempt }, this.props.children);
  }
}

const styles = StyleSheet.create({
  card: {
    borderRadius: 16,
    borderWidth: 1,
    padding: 16,
    gap: 10,
    alignItems: 'flex-start',
  },
  title: { fontSize: 16, fontWeight: '700' },
  desc: { fontSize: 13, lineHeight: 19 },
});
