import { HttpResponse, type DefaultBodyType } from 'msw';

/** MSW 通用工具：FR-013 信封出参 / 分页切片 / 查询参数解析。 */

/** 成功信封（Result.ok，成功码 "0"）。 */
export function ok<T>(data: T, status = 200): HttpResponse<DefaultBodyType> {
  return HttpResponse.json({ success: true, code: '0', message: null, data }, { status });
}

/** 失败信封（Result.fail）。 */
export function fail(code: string, message: string, status = 400): HttpResponse<DefaultBodyType> {
  return HttpResponse.json({ success: false, code, message, data: null }, { status });
}

/** 分页信封（契约对齐 PageResult）。 */
export function page<T>(records: T[], total?: number): HttpResponse<DefaultBodyType> {
  return ok({ records, total: total ?? records.length });
}

/** 空体 2xx（void 端点豁免不出信封，Modules.md 3.1.1）。 */
export function empty(): HttpResponse<DefaultBodyType> {
  return new HttpResponse(null, { status: 200 });
}

/** 解析请求 URL（request.url 为绝对地址，返回 URL 便于 pathname/searchParams 取用）。 */
export function urlOf(request: Request): URL {
  return new URL(request.url);
}

/** 取分页参数（page 1 起、size 正整数，缺省 1/10）。 */
export function pagingOf(request: Request): { page: number; size: number } {
  const params = urlOf(request).searchParams;
  return {
    page: Math.max(1, Number(params.get('page') ?? 1) || 1),
    size: Math.max(1, Number(params.get('size') ?? 10) || 10),
  };
}

/** 分页切片（含越界空页回退语义由前端 useCrud 负责，这里只做常规切片）。 */
export function slicePage<T>(records: T[], pagination: { page: number; size: number }): T[] {
  const start = (pagination.page - 1) * pagination.size;
  return records.slice(start, start + pagination.size);
}

/** 读 JSON 请求体（非 JSON 体回退空对象）。 */
export async function bodyOf(request: Request): Promise<Record<string, unknown>> {
  try {
    return (await request.json()) as Record<string, unknown>;
  } catch {
    return {};
  }
}
