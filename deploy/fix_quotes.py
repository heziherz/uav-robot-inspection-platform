#!/usr/bin/env python3
"""Fix quoting issues in write_chapter4.py by converting double-quoted text args to single-quoted."""
import re

filepath = r'D:/school/all/项目/企业项目实践/deploy/write_chapter4.py'

with open(filepath, 'r', encoding='utf-8') as f:
    lines = f.readlines()

fixed_lines = []
fix_count = 0
for line in lines:
    stripped = line.rstrip('\n')
    
    # For make_para(doc, "...") and make_caption(doc, "..."):
    m = re.match(r'^(make_para|make_caption)\(doc, "(.+)"\)\s*$', stripped)
    if m:
        func_name = m.group(1)
        text = m.group(2)
        fixed_lines.append(f"{func_name}(doc, '{text}')\n")
        fix_count += 1
        continue
    
    # For make_heading(doc, "...", level):
    m = re.match(r'^(make_heading)\(doc, "(.+)", (\d+)\)\s*$', stripped)
    if m:
        func_name = m.group(1)
        text = m.group(2)
        level = m.group(3)
        fixed_lines.append(f"{func_name}(doc, '{text}', {level})\n")
        fix_count += 1
        continue
    
    # For make_para with extra args: make_para(doc, "...", bold=True, ...)
    m = re.match(r'^(make_para)\(doc, "(.+)", (.+)\)\s*$', stripped)
    if m:
        func_name = m.group(1)
        text = m.group(2)
        rest = m.group(3)
        fixed_lines.append(f"{func_name}(doc, '{text}', {rest})\n")
        fix_count += 1
        continue
    
    fixed_lines.append(line)

with open(filepath, 'w', encoding='utf-8') as f:
    f.writelines(fixed_lines)

print(f"Fixed {fix_count} lines out of {len(lines)} total")

# Verify no syntax errors by compiling
import py_compile
try:
    py_compile.compile(filepath, doraise=True)
    print("Syntax check: PASSED")
except py_compile.PyCompileError as e:
    print(f"Syntax check: FAILED - {e}")
