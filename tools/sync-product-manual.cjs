/**
 * sync-product-manual.cjs —— 把产品说明书同步到两份代码副本
 *
 * 为什么要有这个脚本：说明书要同时活在三个地方 ——
 *   ① docs/product-manual.md        作者手写的唯一来源
 *   ② App 端 ai/ProductManual.kt     拼进 chat 的 system 提示，真机上生效的就是它
 *   ③ 预览端 preview-v7.js 的常量     作为「规格」存在（预览不真调 API）
 * 三份手抄一定会漂移。所以只维护 ①，②③ 由本脚本生成；
 * 用 `--check` 可以只校验不写入，接进回归里防漂移。
 *
 * 用法：
 *   node tools/sync-product-manual.cjs           # 生成/更新 ② ③
 *   node tools/sync-product-manual.cjs --check   # 只校验，不一致就退出码 1
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const SRC = path.join(ROOT, 'docs', 'product-manual.md');
const KT = path.join(ROOT, 'app', 'src', 'main', 'java', 'com', 'lvjian', 'app', 'ai', 'ProductManual.kt');
const JS = path.join(ROOT, 'web-preview', 'preview-v7.js');

const BEGIN = '/* >>> PRODUCT_MANUAL:BEGIN';
const END = '/* <<< PRODUCT_MANUAL:END */';
/** 预览端插入位置：脚本所有函数定义之后、首次 render 之前 */
const JS_ANCHOR = '/* ================= 启动 ================= */';

/** 从 .md 读出正文（去掉首尾空白；末尾统一补一个换行，方便排版） */
function readManual() {
  const raw = fs.readFileSync(SRC, 'utf8').replace(/\r\n/g, '\n');
  return raw.trim();
}

// ---------- 目标文件 ②：Kotlin ----------
function kotlinSource(text) {
  // Kotlin 的原始字符串里 $ 仍是模板起始符，必须转义；这份说明书目前没有 $，
  // 但留一道保险，免得以后加进价格之类的字样就编译不过
  const safe = text.replace(/\$/g, '${"$"}');
  return `// 蛋黄的产品知识库：用户问「功能怎么用 / 在哪打开 / 为什么要设」时以这份为准。
//
// ⚠️ 本文件由 docs/product-manual.md **生成**，不要直接改这里 ——
//    改 docs/product-manual.md，然后跑：node tools/sync-product-manual.cjs
//    （预览端 preview-v7.js 里的同一份副本也由那个脚本一起同步，避免两边漂移）
package com.lvjian.app.ai

object ProductManual {
    /** 说明书全文。拼进 chat 的 system 提示，见 AiClient.chat */
    val TEXT: String = """
${safe}
""".trimIndent()
}
`;
}

// ---------- 目标文件 ③：预览端常量 ----------
function jsBlock(text) {
  // 模板字符串里要转义反引号和 ${；这份说明书目前两者都没有，同样留保险
  const safe = text.replace(/\\/g, '\\\\').replace(/`/g, '\\`').replace(/\$\{/g, '\\${');
  return `${BEGIN} —— 由 tools/sync-product-manual.cjs 从 docs/product-manual.md 生成，不要手改这一段 */
/**
 * 蛋黄的产品知识库。App 端真机上会把这份内容拼进 chat 的 system 提示
 * （见 ai/ProductManual.kt 与 AiClient.chat）；预览这里不真调 API，
 * 所以它在这里的角色是「规格」——两边是否一致由 --check 模式守。
 */
const PRODUCT_MANUAL = \`${safe}\`;
${END}`;
}

/** 在预览端 js 里替换（或首次插入）标记块 */
function spliceJs(source, block) {
  const b = source.indexOf(BEGIN);
  const e = source.indexOf(END);
  if (b >= 0 && e > b) {
    return source.slice(0, b) + block + source.slice(e + END.length);
  }
  const at = source.indexOf(JS_ANCHOR);
  if (at < 0) throw new Error('在 preview-v7.js 里找不到插入锚点：' + JS_ANCHOR);
  return source.slice(0, at) + block + '\n\n' + source.slice(at);
}

function main() {
  const check = process.argv.includes('--check');
  const text = readManual();
  const kt = kotlinSource(text);
  // 源码发布包可不包含开发用的网页预览；存在预览时仍同步它。
  const hasPreview = !process.argv.includes('--android-only') && fs.existsSync(JS);
  const js = hasPreview ? spliceJs(fs.readFileSync(JS, 'utf8'), jsBlock(text)) : null;

  const problems = [];
  const ktOld = fs.existsSync(KT) ? fs.readFileSync(KT, 'utf8') : null;
  if (ktOld !== kt) problems.push(path.relative(ROOT, KT));
  if (hasPreview && fs.readFileSync(JS, 'utf8') !== js) problems.push(path.relative(ROOT, JS) + ' 的 PRODUCT_MANUAL 段');

  if (check) {
    if (problems.length === 0) {
      console.log(`PASS | 说明书与${hasPreview ? '两份' : 'Android'}代码副本一致（源 ${text.length} 字）`);
      process.exit(0);
    }
    console.log('FAIL | 说明书副本与 docs/product-manual.md 不一致：');
    problems.forEach(p => console.log('       ' + p));
    console.log('       跑 node tools/sync-product-manual.cjs 同步');
    process.exit(1);
  }

  if (problems.length === 0) {
    console.log('已是最新，无需改动。');
    return;
  }
  fs.mkdirSync(path.dirname(KT), { recursive: true });
  fs.writeFileSync(KT, kt);
  if (hasPreview) fs.writeFileSync(JS, js);
  console.log('已同步：');
  problems.forEach(p => console.log('  ' + p));
}

main();
