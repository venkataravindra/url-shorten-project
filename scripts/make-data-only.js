// Splits an H2 "SCRIPT" backup into statements (quote-aware) and writes only the INSERT statements.
// Usage: node scripts/make-data-only.js backup/agentic-full-backup.sql backup/agentic-data-only.sql
const fs = require('fs');
const [, , inFile, outFile] = process.argv;
const text = fs.readFileSync(inFile, 'utf8');
const stmts = [];
let start = 0, inStr = false, inId = false;
for (let i = 0; i < text.length; i++) {
  const c = text[i];
  if (inStr) { if (c === "'") { if (text[i + 1] === "'") i++; else inStr = false; } continue; }
  if (inId) { if (c === '"') { if (text[i + 1] === '"') i++; else inId = false; } continue; }
  if (c === "'") inStr = true;
  else if (c === '"') inId = true;
  else if (c === '-' && text[i + 1] === '-' && (i === 0 || text[i - 1] === '\n')) { const nl = text.indexOf('\n', i); i = nl < 0 ? text.length : nl; start = i + 1; }
  else if (c === ';') { stmts.push(text.slice(start, i + 1).trim()); start = i + 1; }
}
const inserts = stmts.filter(s => /^INSERT INTO /i.test(s));
const out = ['-- Agentic platform: DATA ONLY (INSERT statements). Run backup/agentic-schema.sql first, on an empty database.',
  'SET REFERENTIAL_INTEGRITY FALSE;', ...inserts, 'SET REFERENTIAL_INTEGRITY TRUE;', ''].join('\n');
fs.writeFileSync(outFile, out, 'utf8');
console.log(`statements: ${stmts.length}, inserts written: ${inserts.length}`);