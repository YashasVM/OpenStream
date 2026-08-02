import { fireEvent, render, screen } from "@testing-library/react";
import { beforeAll, describe, expect, it, vi } from "vitest";
import { afterEach } from "vitest";
import { cleanup } from "@testing-library/react";
import "@testing-library/jest-dom/vitest";
import { App } from "./App";

beforeAll(() => vi.stubGlobal("matchMedia", vi.fn().mockReturnValue({ matches: false })));
afterEach(cleanup);

describe("Studio shell", () => {
  it("renders four typed preview placeholders with non-colour health text", async () => {
    render(<App />);
    expect(await screen.findByLabelText("Camera 4, warning")).toBeVisible();
    expect(screen.getAllByRole("article")).toHaveLength(4);
    expect(screen.getByText("No media sessions in Studio")).toBeVisible();
  });

  it("offers an accessible theme toggle", () => {
    render(<App />);
    const toggle = screen.getByRole("button", { name: "Use dark theme" });
    fireEvent.click(toggle);
    expect(screen.getByRole("button", { name: "Use light theme" })).toBeVisible();
  });
});
