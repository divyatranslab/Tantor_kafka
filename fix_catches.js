const fs = require('fs');
const path = require('path');

function walk(dir) {
    let results = [];
    const list = fs.readdirSync(dir);
    list.forEach(function(file) {
        file = dir + '/' + file;
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

const files = walk('./tantor-ui/src');
let changed = 0;

files.forEach(file => {
    const content = fs.readFileSync(file, 'utf8');
    // We want to replace .catch(() => ...) with nothing.
    // The regex matches .catch(() => {}), .catch(() => []), .catch(() => null), .catch(() => ({}))
    const regex = /\.catch\(\s*\(\s*\)\s*=>\s*(\{\s*\}|\[\s*\]|null|\(\s*\{\s*\}\s*\))\s*\)/g;
    
    if (regex.test(content)) {
        const newContent = content.replace(regex, '');
        fs.writeFileSync(file, newContent, 'utf8');
        console.log('Fixed ' + file);
        changed++;
    }
});

console.log('Changed ' + changed + ' files.');
