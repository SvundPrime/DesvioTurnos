import * as Select from "@radix-ui/react-select";

export default function SelectControl({ value, onChange, options, placeholder, styles }) {
  const selected = options.find(o => o.id === value);

  return (
    <Select.Root value={value} onValueChange={onChange}>
      <Select.Trigger style={styles.trigger} aria-label="selector">
        <Select.Value placeholder={placeholder}>
          {selected?.label ?? placeholder}
        </Select.Value>
        <Select.Icon style={{ opacity: 0.8 }}>▾</Select.Icon>
      </Select.Trigger>

      <Select.Portal>
        <Select.Content style={styles.content} position="popper" sideOffset={8}>
          <Select.ScrollUpButton style={styles.scrollBtn}>▲</Select.ScrollUpButton>

          <Select.Viewport style={styles.viewport}>
            {options.map((o) => (
              <Select.Item key={o.id} value={o.id} style={styles.item}>
                <Select.ItemText>{o.label}</Select.ItemText>
              </Select.Item>
            ))}
          </Select.Viewport>

          <Select.ScrollDownButton style={styles.scrollBtn}>▼</Select.ScrollDownButton>
        </Select.Content>
      </Select.Portal>
    </Select.Root>
  );
}
