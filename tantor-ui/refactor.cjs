const fs = require('fs');
const path = require('path');

function walk(dir) {
    let results = [];
    const list = fs.readdirSync(dir);
    list.forEach(file => {
        file = path.join(dir, file);
        const stat = fs.statSync(file);
        if (stat && stat.isDirectory()) {
            results = results.concat(walk(file));
        } else {
            if (file.endsWith('.tsx') || file.endsWith('.ts')) {
                results.push(file);
            }
        }
    });
    return results;
}

const files = walk(path.join(__dirname, 'src'));

files.forEach(file => {
    if (file.includes('apiClient.ts') || file.includes('KeycloakService.ts')) return;

    let content = fs.readFileSync(file, 'utf8');

    // Replace standalone fetch(
    const fetchRegex = /(?<!\bapi|window\.)\bfetch\(/g;

    if (fetchRegex.test(content)) {
        content = content.replace(fetchRegex, 'apiFetch(');

        // Calculate relative path
        const dir = path.dirname(file);
        const apiPath = path.join(__dirname, 'src', 'lib', 'apiClient.ts');
        let rel = path.relative(dir, apiPath).replace(/\\/g, '/');
        if (!rel.startsWith('.')) rel = './' + rel;

        // Add import at the top
        const importStmt = `import { apiFetch } from '${rel}';\n`;

        // Insert after the last import, or at the top
        const importMatch = [...content.matchAll(/^import .*$/gm)];
        if (importMatch.length > 0) {
            const lastMatch = importMatch[importMatch.length - 1];
            const insertPos = lastMatch.index + lastMatch[0].length + 1;
            content = content.slice(0, insertPos) + importStmt + content.slice(insertPos);
        } else {
            content = importStmt + content;
        }

        // Write back
        fs.writeFileSync(file, content, 'utf8');
        console.log('Updated ' + file);
    }
});
