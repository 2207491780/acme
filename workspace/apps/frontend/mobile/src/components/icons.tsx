import { View } from 'react-native';
import type { Platform } from '@acme/core';
import { PLATFORM_COLOR, onAccent } from '../theme';

// 纯 View 组合的矢量图标，避免引入未在 project.md 登记的图标依赖。

export function IconDashboard({ color, size = 22 }: { color: string; size?: number }) {
  const cell = size * 0.42;
  return (
    <View style={{ width: size, height: size, flexDirection: 'row', flexWrap: 'wrap', gap: size * 0.12 }}>
      {[0, 1, 2, 3].map((i) => (
        <View
          key={i}
          style={{ width: cell, height: cell, borderRadius: 3, backgroundColor: color, opacity: i === 0 ? 1 : 0.5 }}
        />
      ))}
    </View>
  );
}

export function IconReleases({ color, size = 22 }: { color: string; size?: number }) {
  return (
    <View style={{ width: size, height: size, justifyContent: 'center', gap: size * 0.16 }}>
      {[0, 1, 2].map((i) => (
        <View key={i} style={{ flexDirection: 'row', alignItems: 'center', gap: size * 0.14 }}>
          <View style={{ width: size * 0.16, height: size * 0.16, borderRadius: 99, backgroundColor: color }} />
          <View style={{ height: 2.5, flex: 1, borderRadius: 2, backgroundColor: color, opacity: 0.55 }} />
        </View>
      ))}
    </View>
  );
}

export function IconApps({ color, size = 22 }: { color: string; size?: number }) {
  return (
    <View
      style={{
        width: size,
        height: size,
        borderRadius: size * 0.28,
        borderWidth: 2.5,
        borderColor: color,
        alignItems: 'center',
        justifyContent: 'center',
      }}
    >
      <View style={{ width: size * 0.3, height: size * 0.3, borderRadius: 99, backgroundColor: color }} />
    </View>
  );
}

export function IconSettings({ color, size = 22 }: { color: string; size?: number }) {
  const knob = size * 0.26;
  return (
    <View style={{ width: size, height: size, justifyContent: 'center', gap: size * 0.2 }}>
      {[0.28, 0.62, 0.44].map((k, i) => (
        <View key={i} style={{ height: 2.5, borderRadius: 2, backgroundColor: color, opacity: 0.5 }}>
          <View
            style={{
              position: 'absolute',
              left: `${k * 100}%`,
              top: -(knob / 2) + 1,
              marginLeft: -(knob / 2),
              width: knob,
              height: knob,
              borderRadius: 99,
              backgroundColor: color,
            }}
          />
        </View>
      ))}
    </View>
  );
}

export function PlatformGlyph({ platform, color, size = 22 }: { platform: Platform; color: string; size?: number }) {
  if (platform === 'web') {
    return (
      <View
        style={{
          width: size,
          height: size,
          borderRadius: 99,
          borderWidth: 2,
          borderColor: color,
          alignItems: 'center',
          justifyContent: 'center',
        }}
      >
        <View style={{ position: 'absolute', width: 2, height: '100%', backgroundColor: color, opacity: 0.6 }} />
        <View style={{ position: 'absolute', width: '100%', height: 2, backgroundColor: color, opacity: 0.6 }} />
      </View>
    );
  }
  if (platform === 'desktop') {
    return (
      <View style={{ width: size, height: size, alignItems: 'center', justifyContent: 'center', gap: 2 }}>
        <View style={{ width: size, height: size * 0.68, borderRadius: 4, borderWidth: 2, borderColor: color }} />
        <View style={{ width: size * 0.42, height: 2, borderRadius: 2, backgroundColor: color }} />
      </View>
    );
  }
  return (
    <View
      style={{
        width: size * 0.66,
        height: size,
        borderRadius: 5,
        borderWidth: 2,
        borderColor: color,
        alignItems: 'center',
        justifyContent: 'flex-end',
        paddingBottom: 2,
      }}
    >
      <View style={{ width: size * 0.22, height: 2, borderRadius: 2, backgroundColor: color }} />
    </View>
  );
}

export function PlatformBadge({ platform, size = 42 }: { platform: Platform; size?: number }) {
  const color = PLATFORM_COLOR[platform];
  return (
    <View
      style={{
        width: size,
        height: size,
        borderRadius: size * 0.3,
        backgroundColor: `${color}1f`,
        borderWidth: 1,
        borderColor: `${color}40`,
        alignItems: 'center',
        justifyContent: 'center',
      }}
    >
      <PlatformGlyph platform={platform} color={color} size={size * 0.5} />
    </View>
  );
}

export function BrandMark({ color, size = 40 }: { color: string; size?: number }) {
  return (
    <View
      style={{
        width: size,
        height: size,
        borderRadius: size * 0.3,
        backgroundColor: color,
        alignItems: 'center',
        justifyContent: 'center',
      }}
    >
      <View style={{ width: size * 0.44, height: size * 0.44, transform: [{ rotate: '45deg' }] }}>
        <View
          style={{
            position: 'absolute',
            top: 0,
            left: 0,
            width: '100%',
            height: '100%',
            borderTopWidth: 3,
            borderLeftWidth: 3,
            borderColor: onAccent,
            borderTopLeftRadius: 4,
          }}
        />
        <View
          style={{
            position: 'absolute',
            bottom: 0,
            right: 0,
            width: '55%',
            height: '55%',
            backgroundColor: onAccent,
            borderRadius: 2,
          }}
        />
      </View>
    </View>
  );
}