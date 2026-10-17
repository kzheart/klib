"""Resolve current grouped modules and immutable, pre-grouping ABI baselines."""
from pathlib import Path


def module_directories(root):
    root = Path(root)
    manifest = root / 'gradle/module-layout.properties'
    if not manifest.is_file():
        # Historical checkouts must stay unchanged; their module names are directories.
        return {path.name: path for path in sorted(root.glob('klib-*')) if path.is_dir()}

    modules = {}
    locations = set()
    for number, raw in enumerate(manifest.read_text(encoding='utf-8').splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith('#'):
            continue
        name, separator, directory = line.partition('=')
        location = Path(directory)
        if (not separator or not name.startswith('klib-') or name != name.strip()
                or not directory or directory != directory.strip()
                or '\\' in line or ':' in line or '=' in directory
                or location.is_absolute() or '..' in location.parts
                or location == Path('.') or name in modules or directory in locations):
            raise ValueError(f'{manifest}:{number}: invalid or duplicate module mapping: {line}')
        modules[name] = root / location
        locations.add(directory)
    if not modules:
        raise ValueError(f'{manifest}: no module mappings')
    return modules


def production_class_directories(root):
    """Return Java/Kotlin main outputs only, never test or test-fixture classes."""
    return [directory
            for module in module_directories(root).values()
            for language in ('java', 'kotlin')
            for directory in (module / 'build/classes' / language / 'main',)
            if directory.is_dir()]
