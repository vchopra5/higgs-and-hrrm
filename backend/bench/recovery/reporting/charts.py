"""Rebuild descriptive figures from frozen full-study counts (no inference)."""
import os
os.environ.setdefault('MPLCONFIGDIR','/tmp/higgs-mpl')
import json, pathlib, sys
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
root=pathlib.Path(sys.argv[1]); data=json.loads((root/'analysis.json').read_text())
assert data['n']==576
actions=json.loads((root/'action-diagnostics.json').read_text())
for group in ['cells','by_depth']:
    for row in data[group]:
        match=next(x for x in actions[group] if all(x[k]==row[k] for k in (['model','arm','depth','type'] if group=='cells' else ['model','arm','depth'])))
        row['action']=match['passed']
out=root/'figures'; out.mkdir(exist_ok=True)
models=['higgs','openrouter']; labels={'higgs':'Higgs','openrouter':'GPT Audio'}
colors={'higgs':'#157A78','openrouter':'#B44C2B'}; markers={'higgs':'o','openrouter':'s'}
types=['normal','impatient','correction','topic_switch','filler','pushback']
names={'normal':'Question + return','impatient':'Impatient summary','correction':'Item correction','topic_switch':'Topic switch + return','filler':'Backchannel continuation','pushback':'Explicit refusal'}
plt.rcParams.update({'font.family':'DejaVu Sans','font.size':10,'axes.spines.top':False,'axes.spines.right':False,'axes.spines.left':False,'axes.spines.bottom':False,'axes.titleweight':'bold','savefig.facecolor':'white'})
def axes_style(ax):
    ax.set_ylim(-4,104); ax.set_yticks([0,50,100],['0%','50%','100%']); ax.set_xticks([2,6,12,20]);ax.set_xlim(1,21)
    ax.grid(axis='y',color='#E1E4E6',linewidth=.6);ax.tick_params(length=0,pad=6);ax.set_axisbelow(True)
def save(fig,name):
    fig.savefig(out/(name+'.png'),dpi=220);fig.savefig(out/(name+'.svg'));plt.close(fig)
for metric,title in [('recovery','Recovery success'),('task','Task success'),('action','Correct tool actions')]:
    fig,axs=plt.subplots(2,3,figsize=(11,6.9),sharex=True,sharey=True)
    for ax,t in zip(axs.flat,types):
        for m in models:
            for arm in ['control','interrupted']:
                rs=sorted([r for r in data['cells'] if r['model']==m and r['type']==t and r['arm']==arm],key=lambda r:r['depth'])
                ax.plot([r['depth'] for r in rs],[100*r[metric]/r['attempted'] for r in rs],color=colors[m],marker=markers[m] if arm=='interrupted' else None,ls='-' if arm=='interrupted' else '--',alpha=1 if arm=='interrupted' else .48,lw=1.8 if arm=='interrupted' else 1.1,markersize=5,label=labels[m]+' / '+arm)
        axes_style(ax);ax.set_title(names[t],loc='left',fontsize=11,pad=12)
    handles,labs=axs[0,0].get_legend_handles_labels();fig.legend(handles,labs,loc='upper left',bbox_to_anchor=(.065,.94),ncol=4,frameon=False,fontsize=9)
    fig.suptitle(title+' across conversation depth',x=.07,y=.99,ha='left',fontsize=17,fontweight='bold')
    fig.supxlabel('Prior user turns',y=.065,fontsize=10)
    fig.text(.07,.015,'Six trials per point: 3 quest templates × 2 repeats. Solid = interrupted prefix; dashed = complete-speech control.\nIntended denominators; one GPT control at depth 12 is ungraded (task/recovery only). Repeats are not independent.',fontsize=8,color='#555555')
    fig.subplots_adjust(top=.82,bottom=.16,left=.07,right=.98,hspace=.38,wspace=.16);save(fig,metric+'-depth-six-types')
fig,axs=plt.subplots(1,3,figsize=(9,3.4),sharey=True)
for ax,metric,title in zip(axs,['task','recovery','action'],['Task success','Recovery success','Correct tool actions']):
    for m in models:
        rs=sorted([r for r in data['by_depth'] if r['model']==m and r['arm']=='interrupted'],key=lambda r:r['depth'])
        ax.plot([r['depth'] for r in rs],[100*r[metric]/r['attempted'] for r in rs],color=colors[m],marker=markers[m],lw=2,label=labels[m])
        for r in rs: ax.annotate(str(r[metric])+'/36',(r['depth'],100*r[metric]/r['attempted']),xytext=(0,-15 if m=='higgs' else 7),textcoords='offset points',ha='center',fontsize=9,color=colors[m])
    axes_style(ax);ax.set_title(title,loc='left',fontsize=11)
axs[0].legend(loc='lower left',frameon=False,fontsize=9)
fig.suptitle('Interrupted trials, pooled across all six types',x=.07,y=.98,ha='left',fontsize=15,fontweight='bold')
fig.supxlabel('Prior user turns',y=.09,fontsize=9);fig.text(.07,.01,'36 trials per model/depth. Numerators shown; each type contributes equally. No population confidence intervals.',fontsize=8,color='#555555')
fig.subplots_adjust(top=.82,bottom=.23,left=.07,right=.98,wspace=.18);save(fig,'interrupted-depth-overview')
fig,axs=plt.subplots(1,2,figsize=(9,4),sharey=True)
for ax,m in zip(axs,models):
    for repeat,style in [(1,'-'),(2,'--')]:
        rs=sorted([r for r in data['by_repeat'] if r['model']==m and r['arm']=='interrupted' and r['repeat']==repeat],key=lambda r:r['depth'])
        ax.plot([r['depth'] for r in rs],[100*r['recovery']/r['attempted'] for r in rs],color=colors[m],ls=style,marker=markers[m],label=f'Repeat {repeat}')
    axes_style(ax);ax.set_title(labels[m],loc='left');ax.legend(frameon=False,loc='lower left',fontsize=9)
fig.suptitle('Recovery varies across repeated samples',x=.08,y=.98,ha='left',fontsize=15,fontweight='bold');fig.supxlabel('Prior user turns',y=.09,fontsize=9)
fig.text(.08,.01,'18 interrupted trials per repeat/depth; identical fixed fixtures, fresh model outputs.',fontsize=8,color='#555555')
fig.subplots_adjust(top=.82,bottom=.23,left=.08,right=.98,wspace=.15);save(fig,'repeat-sensitivity')
print(out)
