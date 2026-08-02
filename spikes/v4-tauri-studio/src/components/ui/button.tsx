import { Slot } from "@radix-ui/react-slot";
import { cva, type VariantProps } from "class-variance-authority";
import type { ButtonHTMLAttributes } from "react";
import { cn } from "../../lib/utils";

const variants = cva("inline-flex min-h-9 items-center justify-center rounded-md px-3 text-sm font-medium transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:pointer-events-none disabled:opacity-50", {
  variants: { variant: { default: "bg-accent text-accent-foreground hover:bg-accent/90", outline: "border border-border bg-surface hover:bg-muted" } },
  defaultVariants: { variant: "default" },
});
type Props = ButtonHTMLAttributes<HTMLButtonElement> & VariantProps<typeof variants> & { asChild?: boolean };
export function Button({ asChild, className, variant, ...props }: Props) {
  const Component = asChild ? Slot : "button";
  return <Component className={cn(variants({ variant }), className)} {...props} />;
}
