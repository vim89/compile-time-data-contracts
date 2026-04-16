#!/usr/bin/env node

import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const paperDir = resolve(__dirname, '..');
const projectMetaPath = join(paperDir, '.olcli.json');
const defaultOutputPath = join(paperDir, 'main.bbl');
const baseUrl = process.env.OVERLEAF_BASE_URL || 'https://www.overleaf.com';
const cookieName = process.env.OVERLEAF_COOKIE_NAME || 'overleaf_session2';

function parseCookieValue(raw) {
  const trimmed = raw.trim();
  if (!trimmed) return '';
  if (!trimmed.includes('=')) return trimmed;
  const parts = trimmed.split(';').map(p => p.trim());
  const named = parts.find(p => p.startsWith(`${cookieName}=`));
  if (named) return named.slice(cookieName.length + 1);
  return '';
}

function getSessionCookie() {
  if (process.env.OVERLEAF_SESSION) return process.env.OVERLEAF_SESSION.trim();

  const candidates = [
    join(process.cwd(), '.olauth'),
    join(paperDir, '.olauth')
  ];

  for (const candidate of candidates) {
    if (!existsSync(candidate)) continue;
    const parsed = parseCookieValue(readFileSync(candidate, 'utf8'));
    if (parsed) return parsed;
  }

  throw new Error(
    'No Overleaf session found. Set OVERLEAF_SESSION or create a local .olauth file.'
  );
}

function extractCsrf(html) {
  const patterns = [
    /<meta[^>]+name=["']ol-csrfToken["'][^>]+content=["']([^"']+)["']/i,
    /<input[^>]+name=["']_csrf["'][^>]+value=["']([^"']+)["']/i,
    /csrfToken["']?\s*[:=]\s*["']([^"']+)["']/i
  ];

  for (const pattern of patterns) {
    const match = html.match(pattern);
    if (match) return match[1];
  }

  throw new Error('Could not find Overleaf CSRF token.');
}

function mergeSetCookies(cookieJar, response) {
  const setCookies = response.headers.getSetCookie?.() || [];
  for (const setCookie of setCookies) {
    const match = setCookie.match(/^([^=]+)=([^;]+)/);
    if (match) cookieJar[match[1]] = match[2];
  }
}

function cookieHeader(cookieJar) {
  return Object.entries(cookieJar)
    .map(([k, v]) => `${k}=${v}`)
    .join('; ');
}

function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

async function main() {
  if (!existsSync(projectMetaPath)) {
    throw new Error(`Missing ${projectMetaPath}`);
  }

  const projectMeta = JSON.parse(readFileSync(projectMetaPath, 'utf8'));
  const projectId = projectMeta.projectId;
  if (!projectId) {
    throw new Error(`No projectId found in ${projectMetaPath}`);
  }

  const sessionCookie = getSessionCookie();
  const cookieJar = { [cookieName]: sessionCookie };
  const projectPageResponse = await fetch(`${baseUrl}/project`, {
    headers: {
      Cookie: cookieHeader(cookieJar),
      'User-Agent': 'fetch-overleaf-bbl/1.0'
    }
  });
  if (!projectPageResponse.ok) {
    throw new Error(`Failed to load Overleaf project page: ${projectPageResponse.status}`);
  }
  mergeSetCookies(cookieJar, projectPageResponse);
  const projectPageHtml = await projectPageResponse.text();
  const csrf = extractCsrf(projectPageHtml);

  let bblUrl = null;
  for (let attempt = 1; attempt <= 5; attempt++) {
    const compileResponse = await fetch(`${baseUrl}/project/${projectId}/compile?enable_pdf_caching=true`, {
      method: 'POST',
      headers: {
        Cookie: cookieHeader(cookieJar),
        'Content-Type': 'application/json',
        'User-Agent': 'fetch-overleaf-bbl/1.0',
        'X-Csrf-Token': csrf
      },
      body: JSON.stringify({
        rootDoc_id: null,
        draft: false,
        check: 'silent',
        incrementalCompilesEnabled: true
      })
    });
    if (!compileResponse.ok) {
      throw new Error(`Compile request failed: ${compileResponse.status}`);
    }
    mergeSetCookies(cookieJar, compileResponse);
    const compileData = await compileResponse.json();
    const outputFiles = compileData.outputFiles || [];
    const bbl = outputFiles.find(file => file.type === 'bbl');
    if (bbl?.url) {
      bblUrl = `${baseUrl}${bbl.url}`;
      break;
    }
    await sleep(attempt * 1000);
  }

  if (!bblUrl) {
    throw new Error('Could not find output.bbl after compile retries.');
  }

  const bblResponse = await fetch(bblUrl, {
    headers: {
      Cookie: cookieHeader(cookieJar),
      'User-Agent': 'fetch-overleaf-bbl/1.0'
    }
  });
  if (!bblResponse.ok) {
    throw new Error(`Failed to download output.bbl: ${bblResponse.status}`);
  }
  const outputPath = process.argv[2] ? resolve(process.argv[2]) : defaultOutputPath;
  mkdirSync(dirname(outputPath), { recursive: true });
  writeFileSync(outputPath, Buffer.from(await bblResponse.arrayBuffer()));
  console.log(`Saved ${outputPath}`);
}

main().catch(error => {
  console.error(error.message);
  process.exit(1);
});
