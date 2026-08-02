import {createContext,useContext,useEffect,useMemo,useState,type PropsWithChildren} from 'react';import {DeterministicStudioAdapter} from './simulator';import type {StudioAdapter,StudioCommand,StudioSnapshot} from './types';
type WithoutRevision<T>=T extends unknown?Omit<T,'expectedRevision'>:never;
const C=createContext<{snapshot:StudioSnapshot;announcement:string;send:(c:WithoutRevision<StudioCommand>)=>Promise<void>}|null>(null);
export function StudioProvider({children,adapter}:PropsWithChildren<{adapter?:StudioAdapter}>){const value=useMemo(()=>adapter??new DeterministicStudioAdapter(),[adapter]);const[snapshot,setSnapshot]=useState(value.getSnapshot());const[announcement,setAnnouncement]=useState('');useEffect(()=>value.subscribe(setSnapshot),[value]);const send=async(c:WithoutRevision<StudioCommand>)=>{const result=await value.dispatch({...c,expectedRevision:snapshot.revision} as StudioCommand);setAnnouncement(result.status==='confirmed'?'Command confirmed':`${result.status}: ${result.reason??'Command not applied'}`)};return <C.Provider value={{snapshot,announcement,send}}>{children}</C.Provider>}
// Shared with feature views; keeping the hook beside its provider makes the adapter boundary explicit.
// eslint-disable-next-line react-refresh/only-export-components
export const useStudio=()=>{const c=useContext(C);if(!c)throw new Error('StudioProvider missing');return c};
