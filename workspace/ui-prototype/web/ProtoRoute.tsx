import { Suspense } from 'react';
import { useParams } from 'react-router-dom';
import { SkeletonCard, ErrorState } from './components/states';
import CommandPalette from './components/CommandPalette';
import { findProto } from './registry';

/** 原型路由分发：/prototype/:id → registry 查表懒加载。
 *  注意：页面外壳（PageShell 徽标/返回/说明）由各页面自持（extra 各不相同），此处不得再包一层。 */
export default function ProtoRoute() {
  const { id } = useParams<{ id: string }>();
  const entry = findProto(id);

  if (!entry) {
    return (
      <div>
        <CommandPalette />
        <ErrorState code={404} title="原型不存在" desc={`没有 id 为「${id}」的原型；回到索引页查看全部原型。`} />
      </div>
    );
  }

  const Component = entry.component;
  return (
    <>
      <CommandPalette />
      <Suspense fallback={<SkeletonCard rows={4} />}>
        <Component />
      </Suspense>
    </>
  );
}
