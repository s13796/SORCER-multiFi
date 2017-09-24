/*
 * Copyright 2015 the original author or authors.
 * Copyright 2015 SorcerSoft.org.
 *  
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package sorcer.core.context.model.srv;

import groovy.lang.Closure;
import net.jini.core.transaction.Transaction;
import net.jini.core.transaction.TransactionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sorcer.co.tuple.ExecDependency;
import sorcer.co.tuple.MogramEntry;
import sorcer.co.tuple.SignatureEntry;
import sorcer.core.context.ModelStrategy;
import sorcer.core.context.model.ent.Entry;
import sorcer.core.context.model.ent.Function;
import sorcer.core.context.model.ent.ProcModel;
import sorcer.core.context.model.ent.Ref;
import sorcer.core.invoker.ServiceInvoker;
import sorcer.core.plexus.FidelityManager;
import sorcer.core.plexus.MorphFidelity;
import sorcer.core.plexus.MultiFiMogram;
import sorcer.core.provider.rendezvous.ServiceModeler;
import sorcer.core.service.Projection;
import sorcer.core.signature.ServiceSignature;
import sorcer.eo.operator;
import sorcer.service.*;
import sorcer.service.modeling.Model;
import sorcer.service.modeling.Functionality;
import sorcer.service.Signature.ReturnPath;

import java.rmi.RemoteException;
import java.util.*;

import static sorcer.eo.operator.*;

/**
 * A Domain is a schematic description or representation of something, especially a system,
 * phenomenon, or service, that accounts for its properties and is used to study its characteristics.
 * Properties of a service model are represented by path of Context with values that depend
 * on other properties and can be evaluated as specified by ths model. Evaluations of the service 
 * model args of the Srv type results in exerting a dynamic federation of services as specified by
 * these args. A rendezvous service provider orchestrating a choreography of the model
 * is a local or remote one specified by a service signature of the model.
 *   
 * Created by Mike Sobolewski on 1/29/15.
 */
public class SrvModel extends ProcModel implements Invocation<Object> {
    private static final Logger logger = LoggerFactory.getLogger(SrvModel.class);

    public static SrvModel instance(Signature builder) throws SignatureException {
        SrvModel model = (SrvModel) sorcer.co.operator.instance(builder);
        model.setBuilder(builder);
        return model;
    }

    public SrvModel() {
        super();
        name = SRV_MODEL;
        setSignature();
        setSubject("srv/model", new Date());
        isRevaluable = true;
    }

    public SrvModel(String name) {
        super(name);
        setSignature();
        setSubject("srv/model", new Date());
        isRevaluable = true;
    }

    public SrvModel(Signature signature) {
        this();
        addSignature(signature);
    }

    public SrvModel(String name, Signature signature) {
        this(name);
        addSignature(signature);
    }

    private void setSignature() {
        subjectPath = "service/model";
        try {
            subjectValue = sig("exert", ServiceModeler.class);
        } catch (SignatureException e) {
            // ignore it;
        }
    }

    private void setSignature(Signature signature) {
        setSignature(null, signature);
    }

    private void setSignature(String path, Signature signature) {
        if (path == null)
            subjectPath = "service/model";
        else
            subjectPath = path;
        subjectValue = signature;
    }

    public boolean isBatch() {
        for (Object s : selectedFidelity.getSelects()) {
            if (s instanceof Signature && ((Signature)s).getType() != Signature.Type.PROC)
                return false;
        }
        return true;
    }

    @Override
    public Object getValue(String path, Arg... args) throws ContextException {
        return getSrvValue(path, args);
    }

    // calls from VarModels to call Srv args of Vars
    public Object getSrvValue(String path, Srv srv, Arg... args) throws ContextException {
        try {
            putValue(path, srv);
        } catch (ContextException e) {
            data.remove(path);
            throw e;
        }
        Object out = getSrvValue(path, args);
        data.remove(path);
        return out;
    }

    // used as execute but renamed to alter polymorphic chaining
    public Object getSrvValue(String path, Arg... args) throws EvaluationException {
        Object val = null;
        try {
            append(args);
            if (path != null) {
                execDependencies(path, args);
                val = get(path);
            } else {
                ReturnPath rp = Arg.getReturnPath(args);
                if (rp != null)
                    val = getReturnValue(rp);
                else
                    val = super.getValue(path, args);
            }

            if (val instanceof Number) {
                return val;
            } else if (val instanceof Srv) {
                Fi srvFi = ((Srv)val).getMultiFi();
                ((FidelityManager)fiManager).reconfigure(Arg.selectFidelities(args));
                if (isChanged()) {
                    ((Srv) val).isValid(false);
                }
                Object carrier = ((Srv) val).asis();
                if (srvFi != null && srvFi.isChanged()) {
                    carrier = ((Srv)val).getData();
                }
                if (carrier instanceof Signature) {
                        return evalSignature((Signature) carrier, path, args);
                } else if (carrier instanceof SignatureEntry){
                    if (((Srv) val).getOut() != null && ((Srv) val).isValueCurrent() && !isChanged())
                        return ((Srv) val).getOut();
                    else {
                        Signature sig = ((SignatureEntry) ((Srv) val).asis()).getItem();
                        val = evalSignature(sig, path, args);
                    }
                } else if (carrier instanceof ServiceFidelity) {
                    Object selection = getFi((ServiceFidelity) carrier, args, path);
                    if (selection instanceof Signature) {
                        val = evalSignature((Signature) selection, path, args);
                    } else if (selection instanceof Evaluation) {
                        val = ((Evaluation)selection).getValue(args);
                    } else {
                        val = selection;
                    }
                } else if (carrier instanceof MorphFidelity) {
                    Object obj = getFi((ServiceFidelity)((MorphFidelity) carrier).getFidelity(), args, path);
                    Object out = null;
                    if (obj instanceof Signature)
                        out = evalSignature((Signature)obj, path);
                    else if (obj instanceof Entry) {
                        ((Function)obj).setScope(this);
                        out = ((Function) obj).getValue(args);
                    }
                    ((MorphFidelity) carrier).setChanged();
                    ((MorphFidelity) carrier).notifyObservers(out);
                    val =  out;
                } else if (carrier instanceof MogramEntry) {
                    val = evalMogram((MogramEntry)carrier, path, args);
                } else if (carrier instanceof ValueCallable && ((Srv) val).getType() == Functionality.Type.LAMBDA) {
                    ReturnPath rp = ((Srv) val).getReturnPath();
                    Object obj = null;
                    if (rp != null && rp.inPaths != null) {
                        Context cxt = getEvaluatedSubcontext(rp.inPaths, args);
                        obj = ((ValueCallable)carrier).call(cxt);
                    } else {
                        obj = ((ValueCallable) carrier).call(this);
                    }
                    ((Srv) get(path)).setOut(obj);
                    if (rp != null && rp.path != null)
                        putValue(((Srv) val).getReturnPath().path, obj);
                    val = obj;
                }  else if (carrier instanceof MultiFiMogram) {
                    Object out = ((MultiFiMogram)carrier).exert(args);
                    Context cxt = null;
                    if (out instanceof Exertion) {
                        cxt = ((Exertion) out).getContext();
                        SignatureReturnPath rt = ((Exertion) out).getProcessSignature().getReturnPath();
                        if (rt != null && rt.getPath() != null) {
                            Object obj = cxt.getReturnValue();
                            putInoutValue(rt.getPath(), obj);
                            ((Srv) get(path)).setOut(obj);
                            out = obj;
                        } else {
                            ((Srv) get(path)).setOut(cxt);
                            out = cxt;
                        }
                    }
                    ((Srv) get(path)).setOut(cxt);
                    val = out;
                } else if (carrier instanceof Client && ((Srv) val).getType() == Functionality.Type.LAMBDA) {
                    // get target entry for this cal
                    String entryPath = ""+((Function)val).getKey();
                    Object out = ((Client)carrier).exec((Service) this.asis(entryPath), this, args);
                    ((Srv) get(path)).setOut(out);
                    val = out;
                } else if (carrier instanceof EntryCollable && ((Srv) val).getType() == Functionality.Type.LAMBDA) {
                    Entry entry = ((EntryCollable)carrier).call(this);
                    ((Srv) get(path)).setOut(entry.get());
                    if (path != entry.getName())
                        putValue(entry.getName(), entry.get());
                    else if (asis(entry.getName()) instanceof Srv) {
                        ((Srv)asis(entry.getName())).setOut(entry.get());
                    }
                    val = entry;
                } else if (carrier instanceof Closure) {
                    Function entry = (Function) ((Closure)carrier).call(this);
                    ((Srv) get(path)).setOut(entry.get());
                    putValue(path, entry.get());
                    if (path != entry.getName())
                        putValue(entry.getName(), entry.get());
                    val = entry;
                } else if (carrier instanceof ServiceInvoker) {
                    val =  ((ServiceInvoker)carrier).invoke(args);
                } else if (carrier instanceof Service && ((Srv) val).getType() == Functionality.Type.LAMBDA) {
                    String[] paths = ((Srv)val).getPaths();
                    Arg[] nargs = null;
                    if (paths == null || paths.length == 0) {
                        nargs = new Arg[]{this};
                    } else {
                        nargs = new Arg[paths.length];
                        for (int i = 0; i < paths.length; i++) {
                            if (!(asis(paths[i]) instanceof Arg))
                                nargs[i] = new Entry(paths[i], asis(paths[i]));
                            else
                                nargs[i] = (Arg) asis(paths[i]);
                        }
                    }
                    Object out = ((Service)carrier).execute(nargs);
                    ((Srv) get(path)).setOut(out);
                    val = out;
                } else {
                    if (carrier == Context.none) {
                        val = getValue(((Srv) val).getName());
                    }
                }
            } else if (val instanceof Entry) {
                // getData applies current fidelity
                if (((Entry)val).getData() instanceof Ref) {
                    // dereferencing Ref and executing
                    Ref ref = ((Ref)((Entry)val).getItem());
                    ref.setScope(this);
                    Object deref = ref.get();
                    if (deref instanceof Evaluation) {
                        if (deref instanceof Scopable) {
                            ((Scopable)deref).setScope(this);
                        }
                        val = ((Evaluation) deref).getValue(args);
                    } else {
                        // assume default dereference of Entry is inner Entry
                        val = ((Entry) deref).get(args);
                    }
                } else {
                    val = ((Entry)val).get(args);
                }
            } else if (val instanceof ServiceFidelity) {
                return ((Entry)((ServiceFidelity)val).getSelect()).get(args);
            } else {
                return super.getValue(path, args);
            }
        } catch (Exception e) {
            throw new EvaluationException(e);
        }

        ((Entry)get(path)).setOut(val);
        return val;
    }

    public Object evalSignature(Signature sig, String path, Arg... args) throws MogramException {
        Context out = execSignature(sig, args);
        if (sig.getReturnPath() != null) {
            Object obj = out.getValue(((ReturnPath)sig.getReturnPath()).path);
            if (obj == null)
                obj = out.getValue(path);
            if (obj != null) {
                ((Srv)get(path)).setOut(obj);
                return obj;
            } else {
                logger.warn("no eval for return path: {} in: {}", ((ReturnPath)sig.getReturnPath()).path, out);
                return out;
            }
        } else {
            return out;
        }
    }

    private Object evalMogram(MogramEntry mogramEntry, String path, Arg... entries)
            throws MogramException, RemoteException, TransactionException {
        Mogram mogram = mogramEntry.asis();
		mogram.setScope(this);
        Mogram out = mogram.exert(entries);
        if (out instanceof Exertion){
            Context outCxt = out.getContext();
            if (outCxt.getReturnPath() != null) {
                Object obj = outCxt.getReturnValue();
                ((Srv)get(path)).setOut(obj);
                return obj;
            } else if (outCxt.asis(Context.RETURN) != null) {
				((Srv)get(path)).setOut(outCxt.asis(Context.RETURN));
				return outCxt.asis(Context.RETURN);
			} else {
                ((Srv) get(path)).setOut(outCxt);
                return outCxt;
            }
        } else if (out instanceof Model) {
            Context outCxt = (Context) ((Model)out).getResponse(entries);
            append(outCxt);
            return outCxt;
        }
        return null;
    }

    protected Service getFi(ServiceFidelity fi, Arg[] entries, String path) throws ContextException {
       Fidelity selected = null;
        List<Fidelity> fiList = Projection.selectFidelities(entries);
        for (Fidelity sfi : fiList) {
            if (sfi.getName().equals(path)) {
                selected = sfi;
                ((Function) asis(path)).isValid(false);
                isChanged();
                break;
            }
        }

        List<Service> choices = fi.getSelects(this);
        for (Service s : choices) {
            if (selected == null && fi.getSelect() != null)
                return fi.getSelect();
            else {
                String selectPath = null;
                if (selected != null) {
                    selectPath = selected.getPath();
                } else {
                    selectPath = ((Fidelity)choices.get(0)).getPath();
                }
                if (((Identifiable)s).getName().equals(selectPath)) {
                    fi.setSelect(s);
                    return s;
                }
            }
        }
        return null;
    }

    public Context execSignature(Signature sig, Arg... items) throws MogramException {
        execDependencies(sig, items);
        return  super.execSignature(sig, items);
    }

    protected void execDependencies(String path, Arg... args) throws ContextException {
        Map<String, List<ExecDependency>> dpm = ((ModelStrategy)mogramStrategy).getDependentPaths();
        if (dpm != null && dpm.get(path) != null) {
            List<ExecDependency> del = dpm.get(path);
            Function entry = entry(path);
            if (del != null && del.size() > 0) {
                for (ExecDependency de : del) {
                    List<Path> dpl = de.getItem();
                    if (de.getType().equals(Functionality.Type.FIDELITY)) {
                        Fidelity deFi = (Fidelity) de.annotation();
                        if (deFi.getOption() == Fi.Type.IF) {
                            if (((Fidelity) entry.getSelectedFidelity()).getName().equals(deFi.getName())) {
                                // apply only to matched fidelity
                                if (dpl != null && dpl.size() > 0) {
                                    for (Path p : dpl) {
                                        getValue(p.path, args);
                                    }
                                }
                            }
                            continue;
                        } else {
                            // first select the requested fidelity
                            entry.getServiceFidelity().setSelect(((Fidelity) de.annotation()).getName());
                        }
                    } else if (de.getType().equals(Functionality.Type.CONDITION)) {
                        Conditional condition = de.getCondition();
                        if (condition.isTrue()) {
                            // apply only if condition is true
                            if (dpl != null && dpl.size() > 0) {
                                for (Path p : dpl) {
                                    getValue(p.path, args);
                                }
                            }
                        }
                        continue;
                    }
                    if (dpl != null && dpl.size() > 0) {
                        for (Path p : dpl) {
                            getValue(p.path, args);
                        }
                    }

                }
            }
        }
    }

    protected void execDependencies(Signature sig, Arg... args) throws ContextException {
        execDependencies(sig.getName(), args);
    }

    /**
     * Appends a signature <code>signature</code> for this model.
     **/
    public void addSignature(Signature signature) {
        if (signature == null)
            return;
        ((ServiceSignature) signature).setOwnerId(getOwnerId());
        selectedFidelity.getSelects().add(signature);
        selectedFidelity.setSelect(signature);
    }

    public void addSignatures(Signature... signatures) {
        if (this.selectedFidelity != null)
            this.selectedFidelity.getSelects().addAll(Arrays.asList(signatures));
        else {
            this.selectedFidelity = new ServiceFidelity(name);
            this.selectedFidelity.getSelects().addAll(Arrays.asList(signatures));
        }
    }

    @Override
    public Context exert(Transaction txn, Arg... entries) throws TransactionException,
            ExertionException, RemoteException {
        Signature signature = null;
        try {
            if (selectedFidelity != null) {
                signature = (Signature) selectedFidelity.getSelect();
            } else if (subjectValue != null && subjectValue instanceof Signature) {
                signature = (Signature)subjectValue;
            }
            if (signature != null) {
                Exertion out = operator.xrt(name, subjectValue, this).exert(txn, entries);
                Exertion xrt = out.exert();
                return xrt.getDataContext();
            } else {
                // evaluate model response
                getResponse(entries);
                return this;
            }
        } catch (Exception e) {
            throw new ExertionException(e);
        }
    }

    public SrvModel clearOutputs() throws EvaluationException, RemoteException {
        Iterator<Map.Entry<String, Object>> i = entryIterator();
        while (i.hasNext()) {
            Map.Entry e = i.next();
            if (e.getValue() instanceof Srv) {
                ((Srv) e.getValue()).setItem(null);
            } else if (e.getValue() instanceof Function && ((Function)e.getValue()).asis() instanceof Evaluation) {
                ((Function)e.getValue()).isValid(false);
            }
        }
        return this;
    }

    public SrvModel getInoutSubcontext(String... paths) throws ContextException {
        // bare-bones subcontext
        SrvModel subcntxt = new SrvModel();
        subcntxt.setSubject(subjectPath, subjectValue);
        subcntxt.setName(getName() + "-subcontext");
        subcntxt.setDomainId(getDomainId());
        subcntxt.setSubdomainId(getSubdomainId());
        if  (paths != null && paths.length > 0) {
            for (int i = 0; i < paths.length; i++)
                subcntxt.putInoutValueAt(paths[i], getValue(paths[i]), tally + 1);
        }
        return subcntxt;
    }
}
