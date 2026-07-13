import React, { useState } from "react";
import { Icon } from "../icons";
import { links, setupSteps } from "../product";

export function SetupStepper() {
  const [active, setActive] = useState(0);
  const step = setupSteps[active];
  return (
    <div className="setup-stepper">
      <div className="step-tabs" role="tablist" aria-label="OpenStream setup steps">
        {setupSteps.map((item, index) => (
          <button key={item.id} type="button" role="tab" id={`tab-${item.id}`} aria-controls={`panel-${item.id}`} aria-selected={active === index} tabIndex={active === index ? 0 : -1} onClick={() => setActive(index)} onKeyDown={(event) => {
            if (event.key !== "ArrowRight" && event.key !== "ArrowLeft") return;
            event.preventDefault();
            const next = event.key === "ArrowRight" ? (index + 1) % setupSteps.length : (index - 1 + setupSteps.length) % setupSteps.length;
            setActive(next);
            document.getElementById(`tab-${setupSteps[next].id}`)?.focus();
          }}>
            <span>{item.number}</span><strong>{item.title}</strong><small>{item.short}</small><Icon name="chevron" size={18} />
          </button>
        ))}
      </div>
      <div className="step-panel" role="tabpanel" id={`panel-${step.id}`} aria-labelledby={`tab-${step.id}`}>
        <div className="step-visual"><img src={step.image} alt={step.alt} /></div>
        <div className="step-detail"><span>STEP {step.number} / 04</span><h3>{step.title}</h3><p>{step.description}</p><a href={links.setup}>Read the illustrated guide <Icon name="external" size={15} /></a></div>
      </div>
    </div>
  );
}
